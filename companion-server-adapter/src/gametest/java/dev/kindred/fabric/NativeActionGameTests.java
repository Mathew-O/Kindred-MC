package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.persistence.SqliteCompanionStore;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtElement;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/** Fault injection holds immutable acknowledgements; the real SQLite writer performs all actual writes. */
public final class NativeActionGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void heldDurableDispatchCannotEquipAfterStop(TestContext context) {
        var writes = new ControlledActions(); writes.holdDispatch = true;
        var f = new NativePaidSkillsGameTests.Fixture(context, writes);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ledger stop"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_HELMET));
                    f.runtime.nativeSkill(f.source, f.id, "native_equip", 0);
                })
                .createAndAdd(() -> context.assertTrue(writes.actualHeld != null && writes.actualHeld.isDone(), "DISPATCHED is durable but its acknowledgement is withheld"))
                .createAndAdd(() -> {
                    context.assertTrue(writes.actualHeld.getNow(null).state() == ActionRecord.State.DISPATCHED, "Real store wrote dispatch" );
                    context.assertTrue(f.runtime.nativeActionStatus(Actor.console(), f.id).phase().equals("DISPATCHING"), "Owner still awaits dispatch acknowledgement");
                    context.assertTrue(f.player().getEquippedStack(EquipmentSlot.HEAD).isEmpty() && f.player().getInventory().getStack(0).getCount() == 1, "Durable row alone never grants world effect");
                    context.assertTrue(f.runtime.nativeActionStatus(Actor.player(java.util.UUID.randomUUID()), f.id) == null, "Private action status respects access");
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                    writes.held.complete(writes.actualHeld.getNow(null));
                })
                .createAndAdd(() -> {
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id);
                    context.assertTrue(action.record() != null && action.record().state() == ActionRecord.State.FAILED, "Unissued body dispatch settles with absence evidence");
                    context.assertTrue(action.record().afterEvidence() != null, "Failure is evidenced rather than guessed");
                    context.assertTrue(f.player().getEquippedStack(EquipmentSlot.HEAD).isEmpty() && f.player().getInventory().getStack(0).getCount() == 1, "Late acknowledgement cannot equip or duplicate");
                    context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(), "Stop retained");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Action and activity IO close without more body work"))
                .completeIfSuccessful();
    }

    private record Reopened(List<ActionRecord> actions, List<StoredNativePlayer> players) { }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 340)
    public void failedVerificationBlocksAndReopenRetainsPhysicalGearAndUncertainty(TestContext context) {
        var writes = new ControlledActions(); writes.failVerification = true;
        var f = new NativePaidSkillsGameTests.Fixture(context, writes);
        var expected = new ItemStack(Items.IRON_HELMET);
        expected.setDamage(9); expected.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Never replay"));
        CompletableFuture<?>[] loaded = new CompletableFuture<?>[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ledger uncertain"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, expected.copy());
                    f.runtime.nativeSkill(f.source, f.id, "native_equip", 0);
                })
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.phase() == StoredNativePlayer.Phase.BLOCKED && !status.connected(), "Unknown post-effect write blocks/disconnects native identity");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", f.id) == 0, "Unknown outcome cannot reconnect/replay blindly");
                    loaded[0] = NativePlayerSaves.read(context.getWorld().getServer().getSavePath(WorldSavePath.PLAYERDATA), f.nativeId);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete() && loaded[0].isDone(), "Native save and metadata writer closed"))
                .createAndAdd(() -> {
                    @SuppressWarnings("unchecked") var saved = (Optional<NativePlayerSaves.Bytes>) loaded[0].getNow(null);
                    var nbt = saved.orElseThrow().decode(context.getWorld().getServer());
                    var inventory = nbt.getList("Inventory", NbtElement.COMPOUND_TYPE);
                    int helmets = 0;
                    for (int i = 0; i < inventory.size(); i++) {
                        var tag = inventory.getCompound(i);
                        context.assertTrue(tag.getByte("Slot") != 0, "Consumed equipment source not recreated in native save");
                        if (tag.getByte("Slot") == 103) {
                            var actual = ItemStack.fromNbt(context.getWorld().getRegistryManager(), tag).orElseThrow();
                            context.assertTrue(actual.getCount() == 1 && ItemStack.areItemsAndComponentsEqual(expected, actual), "Physical equipped stack/components remain saved despite metadata fault");
                            helmets++;
                        }
                    }
                    context.assertTrue(helmets == 1, "Exactly one saved helmet");
                    loaded[1] = SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).thenCompose(store ->
                            store.unresolvedActions(f.id).thenCombine(store.nativePlayers(), Reopened::new)
                                    .handle((result, error) -> store.closeAsync().thenApply(ignored -> {
                                        if (error != null) throw new java.util.concurrent.CompletionException(error);
                                        return result;
                                    })).thenCompose(future -> future));
                })
                .createAndAdd(() -> {
                    context.assertTrue(loaded[1].isDone() && !loaded[1].isCompletedExceptionally(), "Real metadata reopen completed");
                    var reopened = (Reopened) loaded[1].getNow(null);
                    context.assertTrue(reopened.actions().size() == 1 && reopened.actions().getFirst().state() == ActionRecord.State.UNCERTAIN, "Unverified dispatch recovers uncertain, never success");
                    context.assertTrue(reopened.players().getFirst().phase() == StoredNativePlayer.Phase.BLOCKED, "Durable native block survives reopen");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void runtimeShutdownWaitsForHeldActionIoWithoutReactivatingBody(TestContext context) {
        var writes = new ControlledActions(); writes.holdDispatch = true;
        var f = new NativePaidSkillsGameTests.Fixture(context, writes);
        CompletableFuture<?>[] saved = new CompletableFuture<?>[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ledger shutdown"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_HELMET));
                    f.runtime.nativeSkill(f.source, f.id, "native_equip", 0);
                })
                .createAndAdd(() -> context.assertTrue(writes.actualHeld != null && writes.actualHeld.isDone(), "Actual dispatch write waits for acknowledgement"))
                .createAndAdd(() -> {
                    f.runtime.stop();
                    context.assertTrue(!f.runtime.shutdownComplete(), "Store cannot close ahead of outstanding action acknowledgement");
                    context.assertTrue(context.getWorld().getServer().getPlayerManager().getPlayer(f.nativeId) == null, "Body disconnected synchronously");
                    writes.held.complete(writes.actualHeld.getNow(null));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Action/allowance IO settles with runtime disabled"))
                .createAndAdd(() -> {
                    var actionId = writes.actualHeld.getNow(null).actionId();
                    saved[0] = SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).thenCompose(store ->
                            store.findAction(actionId).handle((row, error) -> store.closeAsync().thenApply(ignored -> {
                                if (error != null) throw new java.util.concurrent.CompletionException(error);
                                return row.orElseThrow();
                            })).thenCompose(future -> future));
                })
                .createAndAdd(() -> {
                    context.assertTrue(saved[0].isDone() && !saved[0].isCompletedExceptionally(), "Closed writer reopened for evidence" );
                    var action = (ActionRecord) saved[0].getNow(null);
                    context.assertTrue(action.state() == ActionRecord.State.FAILED && action.afterEvidence() != null, "Untaken action flushed with absence evidence before store close");
                    context.assertTrue(context.getWorld().getServer().getPlayerManager().getPlayer(f.nativeId) == null, "Late acknowledgement never recreates body");
                })
                .completeIfSuccessful();
    }

    private static final class ControlledActions implements UnaryOperator<ActionAdmission.Writer> {
        boolean holdDispatch, failVerification;
        CompletableFuture<ActionRecord> actualHeld, held;
        @Override public ActionAdmission.Writer apply(ActionAdmission.Writer delegate) {
            return new ActionAdmission.Writer() {
                public CompletableFuture<ActionRecord> prepare(ActionRecord intent) { return delegate.prepare(intent); }
                public CompletableFuture<ActionRecord> transition(ActionRecord next, long revision) {
                    if (failVerification && next.state() == ActionRecord.State.VERIFIED) {
                        failVerification = false;
                        return CompletableFuture.failedFuture(new IllegalStateException("Injected missing verification acknowledgement"));
                    }
                    var actual = delegate.transition(next, revision);
                    if (holdDispatch && next.state() == ActionRecord.State.DISPATCHED) {
                        holdDispatch = false; actualHeld = actual; held = new CompletableFuture<>(); return held;
                    }
                    return actual;
                }
            };
        }
    }
}
