package dev.kindred.fabric;

import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * Native player survival, eating, and retreat qualification for E-07a:
 * - Health-aware autonomous eating with inventory food search and hotbar swapping
 * - 100% item conservation during food consumption and natural health regeneration
 * - Environmental hazard detection (magma block, fire, etc.) and evasive retreat
 * - Operator retreat dispatch, arrival verification, and synchronous stop cancellation
 */
public final class NativeSurvivalGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 360)
    public void damagedPlayerEatsFromMainInventoryWithAutonomyAndConservesItems(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "SurvivalEater"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Damaged player with hunger deficit, no food in hotbar 0..8, but apples in inventory slot 14
                    f.player().setHealth(10.0f);
                    f.player().getHungerManager().setFoodLevel(14);
                    f.player().getHungerManager().setSaturationLevel(0);
                    for (int s = 0; s < 9; s++) {
                        f.player().getInventory().setStack(s, ItemStack.EMPTY);
                    }
                    f.player().getInventory().setStack(14, new ItemStack(Items.APPLE, 2));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_eat"),
                            "Autonomous eat completes under paid activity session");
                    int totalApples = countItem(f, Items.APPLE);
                    context.assertTrue(totalApples == 1, "Exactly one apple consumed from inventory (1 remaining)");
                    context.assertTrue(f.player().getHungerManager().getFoodLevel() >= 18, "Hunger restored to enable healing");
                    context.assertTrue(f.player().getHealth() >= 10.0f, "Player health maintained or regenerating");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void playerOnHazardousMagmaBlockRetreatsToSafeGroundAutonomously(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "HazardEvader"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Replace ground directly under player's feet with hazardous magma block
                    BlockPos standingGround = f.player().getBlockPos().down();
                    context.getWorld().setBlockState(standingGround, Blocks.MAGMA_BLOCK.getDefaultState());
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_retreat"),
                            "Autonomous hazard retreat completes");
                    BlockPos currentFeet = f.player().getBlockPos();
                    BlockPos currentGround = currentFeet.down();
                    var currentGroundState = context.getWorld().getBlockState(currentGround);
                    context.assertTrue(!currentGroundState.isOf(Blocks.MAGMA_BLOCK), "Player retreated off hazardous magma block");
                    context.assertTrue(currentGroundState.isOf(Blocks.STONE), "Player safely standing on solid stone ground");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 280)
    public void operatorRetreatSkillMovesAndSettles(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        double[] startX = new double[1];

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "TacticalRunner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    startX[0] = f.player().getX();
                    context.assertTrue(f.runtime.nativeRetreat(f.source, f.id, "east", 2) == 1, "Native retreat requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_retreat"),
                            "Native retreat completed and settled");
                    context.assertTrue(f.player().getX() > startX[0] + 1.5, "Player moved ~2 blocks east");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 260)
    public void stopCancelsRetreatSynchronously(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        double[] startX = new double[1];

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "StopTester"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    startX[0] = f.player().getX();
                    context.assertTrue(f.runtime.nativeRetreat(f.source, f.id, "east", 3) == 1, "Retreat dispatched");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getX() > startX[0] + 0.15, "Retreat physically started"))
                .createAndAdd(() -> {
                    // While in motion, stop synchronously
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                    context.assertTrue(!f.player().isSprinting(), "Stop clears sprint synchronously");
                    context.assertTrue(f.runtime.nativeRetreat(f.source, f.id, "east", 3) == 0, "Stopped player cannot restart retreat");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE"), "Cancelled retreat settles into IDLE");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static int countItem(NativePaidSkillsGameTests.Fixture f, net.minecraft.item.Item item) {
        int total = 0;
        var inv = f.player().getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }
}
