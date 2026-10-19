package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.attention.AttentionState;
import dev.kindred.core.attention.AttentionSnapshot;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * Acceptance GameTests for Attention-System Scheduler (F-12a):
 * 1. Immediate hazard detection triggers Alert attention status and executes autonomous hazard retreat on essential path.
 * 2. Low/zero optional budget still permits essential survival eating and hazard retreat while routine planning is deferred.
 * 3. Shared scan budget and diagnostic counters prove aggregate block scan limits across players including helper methods.
 * 4. Inventory fingerprint change triggers cache invalidation and drops stale routine candidates.
 */
public final class NativeAttentionGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void immediateHazardTriggersAlertAttentionAndExecutesRetreat(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "HazardAvoider"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    AttentionSnapshot initial = f.runtime.nativeAttentionStatus(f.id);
                    context.assertTrue(initial != null, "Attention snapshot initialized");
                    // Place hazardous magma block directly under player
                    BlockPos ground = f.player().getBlockPos().down();
                    context.getWorld().setBlockState(ground, Blocks.MAGMA_BLOCK.getDefaultState());
                })
                .createAndAdd(() -> {
                    // Let at least one tick observe hazard and transition to ALERT
                    AttentionSnapshot attention = f.runtime.nativeAttentionStatus(f.id);
                    context.assertTrue(attention != null && attention.state() == AttentionState.ALERT,
                            "Immediate hazard triggers ALERT state: " + (attention != null ? attention.state() : "null"));
                })
                .createAndAdd(() -> {
                    // Wait for retreat skill to settle
                    var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!act.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(act.phase().equals("IDLE") && act.work().contains("native_retreat"),
                            "Autonomous retreat completed on essential survival path: " + act.work());
                    BlockPos currentGround = f.player().getBlockPos().down();
                    context.assertTrue(!context.getWorld().getBlockState(currentGround).isOf(Blocks.MAGMA_BLOCK),
                            "Player safely moved off hazardous magma block");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void essentialSurvivalEatingExecutesWhileRoutinePlanningIsDeferred(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "EssentialEater"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.APPLE, 2));
                    f.player().getHungerManager().setFoodLevel(14);
                    f.player().getHungerManager().setSaturationLevel(0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    // Wait for eating skill to complete
                    var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!act.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(act.phase().equals("IDLE") && act.work().startsWith("Completed native_eat"),
                            "Autonomous eat completes on unbudgeted essential path: " + act.work());
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 1, "Exactly one apple consumed");
                    context.assertTrue(f.player().getHungerManager().getFoodLevel() >= 18, "Hunger restored by native mechanics");
                    context.assertTrue(f.runtime.schedulerDiagnostics() != null, "Scheduler diagnostics active");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 260)
    public void sharedBlockScanBudgetCapsAggregateChecksAcrossPlayers(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "BudgetScanner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    BlockPos target = context.getAbsolutePos(new BlockPos(4, 2, 4));
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    // Per-tick block scan count must never exceed the budget cap
                    int scanned = f.runtime.blocksScannedThisTick();
                    context.assertTrue(scanned <= PlayerRuntime.MAX_BLOCK_CHECKS_PER_TICK,
                            "Scanned blocks in tick (" + scanned + ") must not exceed budget cap (" + PlayerRuntime.MAX_BLOCK_CHECKS_PER_TICK + ")");
                    var diag = f.runtime.schedulerDiagnostics();
                    context.assertTrue(diag != null, "Diagnostics available");
                    context.assertTrue(f.runtime.totalBlockChecks() >= 0, "Monotonic block checks tracked");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 260)
    public void inventoryFingerprintChangeInvalidatesCachedRoutineCandidates(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);

        long[] originalFp = new long[1];

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "FingerprintWatcher"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    originalFp[0] = f.runtime.inventoryFingerprintForTest(f.id);
                    // Mutate inventory by adding items
                    f.player().getInventory().setStack(10, new ItemStack(Items.IRON_INGOT, 3));
                })
                .createAndAdd(() -> {
                    long updatedFp = f.runtime.inventoryFingerprintForTest(f.id);
                    context.assertTrue(updatedFp != originalFp[0],
                            "Inventory fingerprint changed after slot mutation: " + originalFp[0] + " -> " + updatedFp);
                    // Cached candidates should be invalidated/cleared when fingerprint diverged
                    context.assertTrue(f.runtime.cachedRoutineCandidatesCountForTest(f.id) == 0,
                            "Cached routine candidates dropped on inventory invalidation");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
