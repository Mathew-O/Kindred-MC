package dev.kindred.fabric;

import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.persistence.SqliteCompanionStore;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: an AI player whose dig a crash cut short, stopped for reconciliation on the next start, is brought back
 * by an operator without anything done twice or any item made (F-31g1). The crash is staged as the kill left it in the
 * restart soak: the player's body saved, its dig recorded as dispatched with the fingerprint of what it held, the process
 * gone before the outcome was written; the next start marks the dig uncertain and blocks the player. Then:
 * <ul>
 *   <li>the block still there and its things as they were: settled not done from the evidence alone;</li>
 *   <li>the block gone and its things as they were: settled done from the evidence alone;</li>
 *   <li>its things not as they were: left blocked until the operator chooses, then settled as chosen.</li>
 * </ul>
 * In each, the settlement is in the Record with who made it and what was seen, the block is as it was staged (nothing dug
 * again), and the player comes back holding exactly what it saved.
 */
public final class NativeSettleGameTests implements FabricGameTest {
    private static final BlockPos TARGET = new BlockPos(6, 2, 6);

    private enum Case { NOT_DONE, DONE, AMBIGUOUS }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "settleNotDone", tickLimit = 2400)
    public void aDigACrashCutShortWithTheBlockStillThereIsSettledNotDoneFromTheEvidence(TestContext context) {
        run(context, Case.NOT_DONE);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "settleDone", tickLimit = 2400)
    public void aDigACrashCutShortWithTheBlockGoneIsSettledDoneFromTheEvidence(TestContext context) {
        run(context, Case.DONE);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "settleAmbiguous", tickLimit = 2400)
    public void aDigTheEvidenceLeavesOpenStaysBlockedUntilTheOperatorChooses(TestContext context) {
        run(context, Case.AMBIGUOUS);
    }

    private void run(TestContext context, Case staged) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var server = context.getWorld().getServer();
        String[] before = new String[1];
        UUID[] action = new UUID[1];
        @SuppressWarnings("unchecked") CompletableFuture<Void>[] seeded = new CompletableFuture[1];
        @SuppressWarnings("unchecked") CompletableFuture<String[]>[] checked = new CompletableFuture[1];
        BlockPos target = context.getAbsolutePos(TARGET);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    var pick = new ItemStack(Items.STONE_PICKAXE);
                    pick.setDamage(7);
                    inventory.setStack(0, pick);
                    inventory.setStack(1, new ItemStack(Items.BREAD, 3));
                    inventory.setStack(9, new ItemStack(Items.COBBLESTONE, 20));
                    context.getWorld().setBlockState(target, Blocks.STONE.getDefaultState());
                    before[0] = InventoryEvidence.capture(inventory).description();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", f.id) == 1, "Ada stopped");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped: Ada's body saved"))
                .createAndAdd(() -> {
                    // The crash: the dig recorded as dispatched, the outcome never written. What it held, as the evidence
                    // before the dig: the real fingerprint, or another inventory's where the evidence must not agree.
                    String evidence = staged == Case.AMBIGUOUS ? "native owned inventory slots=41; sha256=" + "0".repeat(64) : before[0];
                    String preconditions = "minecraft:stone at " + target.toShortString();
                    seeded[0] = CompletableFuture.runAsync(() -> {
                        var store = SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).join();
                        try {
                            var lease = new ActionLease(f.id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
                            var prepared = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "native_break", preconditions)).join();
                            action[0] = store.transitionAction(prepared.dispatched(evidence), 0).join().actionId();
                        } finally {
                            store.closeAsync().join();
                        }
                    });
                    if (staged == Case.DONE) context.getWorld().setBlockState(target, Blocks.AIR.getDefaultState());
                })
                .createAndAdd(() -> context.assertTrue(seeded[0].isDone() && !seeded[0].isCompletedExceptionally(), "The crash staged"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Restarted"))
                .createAndAdd(() -> {
                    context.assertTrue(!f.runtime.ledgerClearForTest(f.id), "Blocked by its uncertain dig");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", f.id) == 0, "Connect refused while blocked");
                    context.assertTrue(f.runtime.settleNative(f.source, f.id, null) == 1, "Settlement begun");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.settlingForTest(f.id), "Settlement over"))
                .createAndAdd(() -> {
                    if (staged == Case.AMBIGUOUS) {
                        // Not plain from the evidence: still blocked; then the operator's choice.
                        context.assertTrue(!f.runtime.ledgerClearForTest(f.id), "Left blocked for the operator");
                        context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", f.id) == 0, "Still refused");
                        context.assertTrue(f.runtime.settleNative(f.source, f.id, "notdone") == 1, "The operator chooses not done");
                    }
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.ledgerClearForTest(f.id), "Ada free once settled"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", f.id) == 1, "Connected"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Nothing made, nothing dug again: what it holds is what it saved, the block as staged.
                    context.assertTrue(InventoryEvidence.capture(f.player().getInventory()).description().equals(before[0]),
                            "Ada holds exactly what it saved");
                    boolean stone = context.getWorld().getBlockState(target).isOf(Blocks.STONE);
                    context.assertTrue(staged == Case.DONE ? !stone : stone, "The block as it was staged");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", f.id) == 1, "Ada stopped");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped to read the Record"))
                .createAndAdd(() -> checked[0] = CompletableFuture.supplyAsync(() -> {
                    var store = SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).join();
                    try {
                        var row = store.findAction(action[0]).join().orElseThrow();
                        var notes = store.worldEvents(Set.of("action.settled"), 0, 10).join();
                        var note = notes.size() == 1 ? notes.get(0) : null;
                        return new String[] {row.state().name(), String.valueOf(notes.size()), note == null ? "" : note.data().get("decided"),
                                note == null ? "" : note.data().get("by"), note == null ? "" : note.subject(), row.detail(),
                                String.valueOf(store.checkActionLedger().join().agrees())};
                    } finally {
                        store.closeAsync().join();
                    }
                }))
                .createAndAdd(() -> {
                    context.assertTrue(checked[0].isDone() && !checked[0].isCompletedExceptionally(), "The Record read");
                    var r = checked[0].join();
                    String state = staged == Case.DONE ? "VERIFIED" : "FAILED";
                    context.assertTrue(r[0].equals(state), "Settled " + state + ": " + r[0] + " / " + r[5]);
                    context.assertTrue(r[1].equals("1"), "One settlement in the Record: " + r[1]);
                    context.assertTrue(r[2].equals(staged == Case.AMBIGUOUS ? "operator" : "evidence"), "Decided by " + r[2]);
                    context.assertTrue(r[3].equals("the console") && r[4].equals(action[0].toString()), "Who and which: " + r[3] + " " + r[4]);
                    context.assertTrue(r[6].equals("true"), "The ledger agrees with the Record");
                })
                .createAndAdd(context::complete);
    }
}
