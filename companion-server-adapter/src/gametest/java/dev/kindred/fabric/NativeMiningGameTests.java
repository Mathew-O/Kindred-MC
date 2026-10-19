package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.persistence.SqliteCompanionStore;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual native log breaks under paid, scoped, claimed and recorded runtime authority. */
public final class NativeMiningGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void recordedMiningUsesOwnedToolAndDoesNotClaimCollection(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos target = target(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready with explicit fixture edit policy"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Miner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    context.getWorld().setBlockState(target.east(), Blocks.OAK_LOG.getDefaultState());
                    context.assertTrue(f.runtime.nativeMine(f.source, f.id, target, 0) == 0, "Known edit policy does not substitute for a work patch");
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Scope acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    context.assertTrue(f.runtime.nativeMine(f.source.withLevel(0), f.id, target, 0) == 0, "Foreign mining denied");
                    context.assertTrue(f.runtime.nativeMine(f.source, f.id, target.east(), 0) == 0, "Matching log outside patch denied");
                    context.assertTrue(f.runtime.nativeMine(f.source, f.id, target, 9) == 0, "Invalid tool slot denied");
                    context.assertTrue(f.runtime.nativeMine(f.source, f.id, target, 0) == 1, "Single scoped log requested");
                    context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.OAK_LOG), "Request is not an instant block break");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_mine"), "Paid native action completes only after verification");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED && action.kind().equals("native_mine")
                            && action.afterEvidence().contains("nativeReceipt=") && action.afterEvidence().contains("drops=unverified"), "Durable receipt records break and honest collection limit");
                    context.assertTrue(context.getWorld().getBlockState(target).isAir() && context.getWorld().getBlockState(target.east()).isOf(Blocks.OAK_LOG), "Exactly the scoped log broke");
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 1 && f.player().getMainHandStack().getDamage() == 1
                            && f.player().getMainHandStack() == f.player().getInventory().getStack(0), "Actual owned axe wears once, without a usable copy");
                    context.assertTrue(activity.chargedMillis() > 0 && activity.chargedMillis() <= 5000, "One acknowledged paid slice");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 240)
    public void defaultServerPolicyKeepsScopedMiningDisabled(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos target = target(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready without edit opt-in"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Policy blocked"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch saved"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeMine(f.source, f.id, target, 0) == 0, "Scope/personality/control do not enable server edits");
                    context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("UNLOADED")
                            && f.runtime.nativeActionStatus(Actor.console(), f.id).phase().equals("NONE"), "Policy rejection creates no reservation or action intent");
                    context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.OAK_LOG), "No world mutation");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 360)
    public void stopCancelsWithoutLateBreakAndExternalChangeBecomesUncertain(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos target = target(context);
        int[] stoppedAt = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mining stop"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Scope saved"))
                .createAndAdd(() -> { f.runtime.nativeLifecycle(f.source, "resume", f.id); f.runtime.nativeMine(f.source, f.id, target, 0); })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeActionStatus(Actor.console(), f.id).phase().equals("ISSUED"), "Timed native break has been issued after durable dispatch"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                    stoppedAt[0] = f.player().age;
                    context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.OAK_LOG), "Barehanded log remains before completion");
                })
                .createAndAdd(() -> context.assertTrue(f.player().age >= stoppedAt[0] + 80, "Observe past ordinary mining completion time"))
                .createAndAdd(() -> {
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.FAILED && action.afterEvidence() != null, "Cancelled intact target produces evidenced no-effect outcome");
                    context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.OAK_LOG), "No delayed native break after stop");
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeMine(f.source, f.id, target, 0) == 1, "Only an explicit new request retries");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeActionStatus(Actor.console(), f.id).phase().equals("ISSUED"), "New mining issued"))
                .createAndAdd(() -> context.getWorld().setBlockState(target, Blocks.STONE.getDefaultState()))
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.phase() == StoredNativePlayer.Phase.BLOCKED && !status.connected(), "Conflicting world evidence blocks instead of replaying");
                    context.assertTrue(f.runtime.nativeActionStatus(Actor.console(), f.id).record().state() == ActionRecord.State.UNCERTAIN, "External replacement never becomes native success");
                    context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.STONE), "Replacement preserved");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 340)
    public void twoNativeMinersShareOneClaimAndBreakOnlyOnce(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos target = target(context);
        UUID[] second = new UUID[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Miner one"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Miner two"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 2
                        && f.runtime.nativeStatuses(Actor.console()).stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED), "Both bodies established"))
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).stream().filter(s -> s.name().equals("Miner two")).findFirst().orElseThrow();
                    second[0] = status.id(); second[1] = status.nativeId();
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                    f.runtime.nativePatch(f.source, second[0], target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending()
                        && !f.runtime.nativeSetupStatus(Actor.console(), second[0]).pending(), "Both scopes saved"))
                .createAndAdd(() -> {
                    var peer = context.getWorld().getServer().getPlayerManager().getPlayer(second[1]);
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    peer.getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id); f.runtime.nativeLifecycle(f.source, "resume", second[0]);
                    context.assertTrue(f.runtime.nativeMine(f.source, f.id, target, 0) == 1 && f.runtime.nativeMine(f.source, second[0], target, 0) == 1, "Both paid requests may contend for one resource");
                })
                .createAndAdd(() -> {
                    var a = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    var b = f.runtime.nativeActivityStatus(Actor.console(), second[0]);
                    context.assertTrue(a.phase().equals("IDLE") && b.phase().equals("IDLE")
                            && (a.work().startsWith("Completed native_mine") || b.work().startsWith("Completed native_mine")), "Winner verifies, other task settles its blocker");
                    int successes = (a.work().startsWith("Completed native_mine") ? 1 : 0) + (b.work().startsWith("Completed native_mine") ? 1 : 0);
                    var peer = context.getWorld().getServer().getPlayerManager().getPlayer(second[1]);
                    context.assertTrue(successes == 1 && f.player().getMainHandStack().getDamage() + peer.getMainHandStack().getDamage() == 1,
                            "One shared claim permits exactly one real break/tool use");
                    context.assertTrue(context.getWorld().getBlockState(target).isAir(), "One native block removed");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 340)
    public void failedMiningVerificationRetainsActualBreakAndRecoversUncertain(TestContext context) {
        AtomicBoolean fail = new AtomicBoolean(true);
        var f = new NativePaidSkillsGameTests.Fixture(context, delegate -> new ActionAdmission.Writer() {
            public CompletableFuture<ActionRecord> prepare(ActionRecord intent) { return delegate.prepare(intent); }
            public CompletableFuture<ActionRecord> transition(ActionRecord next, long revision) {
                if (next.kind().equals("native_mine") && next.state() == ActionRecord.State.VERIFIED && fail.compareAndSet(true, false))
                    return CompletableFuture.failedFuture(new IllegalStateException("Injected missing mining verification"));
                return delegate.transition(next, revision);
            }
        }, true);
        BlockPos target = target(context);
        ServerPlayerEntity[] body = new ServerPlayerEntity[1];
        CompletableFuture<?>[] reopened = new CompletableFuture<?>[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Uncertain mine"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Scope saved"))
                .createAndAdd(() -> {
                    body[0] = f.player(); body[0].getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id); f.runtime.nativeMine(f.source, f.id, target, 0);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().phase() == StoredNativePlayer.Phase.BLOCKED, "Unacknowledged outcome blocks native identity");
                    context.assertTrue(context.getWorld().getBlockState(target).isAir() && body[0].getInventory().getStack(0).getCount() == 1
                            && body[0].getInventory().getStack(0).getDamage() == 1, "Actual break and real tool wear are never rolled back or copied");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", f.id) == 0, "Unknown break cannot replay by reconnect");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Writer closed"))
                .createAndAdd(() -> reopened[0] = SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).thenCompose(store ->
                        store.unresolvedActions(f.id).handle((rows, error) -> store.closeAsync().thenApply(ignored -> {
                            if (error != null) throw new java.util.concurrent.CompletionException(error);
                            return rows;
                        })).thenCompose(future -> future)))
                .createAndAdd(() -> {
                    context.assertTrue(reopened[0].isDone() && !reopened[0].isCompletedExceptionally(), "Real metadata reopened");
                    @SuppressWarnings("unchecked") var rows = (List<ActionRecord>) reopened[0].getNow(null);
                    context.assertTrue(rows.size() == 1 && rows.getFirst().state() == ActionRecord.State.UNCERTAIN && rows.getFirst().kind().equals("native_mine"),
                            "Missing verification recovers uncertain rather than guessed success");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void recordedStoneMiningUsesWoodenPickaxeAndBreaksBlock(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos target = target(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready with explicit fixture edit policy"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "StoneMiner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.STONE.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Scope acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    f.player().getInventory().setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
                    context.assertTrue(f.runtime.nativeMine(f.source, f.id, target, 0) == 1, "Stone mining requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_mine"), "Stone mining completed and settled");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED && action.kind().equals("native_mine"), "Durable receipt verified");
                    context.assertTrue(context.getWorld().getBlockState(target).isAir(), "Stone block broken");
                    context.assertTrue(f.player().getInventory().getStack(0).getDamage() >= 1, "Wooden pickaxe wears durability");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static BlockPos target(TestContext context) { return context.getAbsolutePos(new BlockPos(3, 2, 5)); }
}
