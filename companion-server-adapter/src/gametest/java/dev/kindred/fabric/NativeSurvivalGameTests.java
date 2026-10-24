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
                    // Autonomy moves on to its next task at once, so check the eating's physical outcome, not a status line.
                    context.assertTrue(countItem(f, Items.APPLE) == 1 && f.player().getHungerManager().getFoodLevel() >= 18,
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

    /**
     * Its reflexes need neither the server's AI world edits nor its work: with edits off, and again with the player
     * idle (the playstyle a new player starts in), it steps off magma, and idle it eats when hungry. Neither changes a
     * block; with edits off, before, it stood on the magma.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "reflexesEditsOff", tickLimit = 400)
    public void withEditsOffItStillStepsOffMagma(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 390);
        context.createTimedTaskRunner()
                .createAndAdd(() -> f.awaitReady(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "EditsOff"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(f.player().getBlockPos().down(), Blocks.MAGMA_BLOCK.getDefaultState());
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).work().startsWith("Completed native_retreat"),
                        "Stepped off the magma: " + f.runtime.nativeActivityStatus(Actor.console(), f.id).work()))
                .createAndAdd(() -> {
                    var ground = context.getWorld().getBlockState(f.player().getBlockPos().down());
                    context.assertTrue(ground.isOf(Blocks.STONE), "On stone now, not magma: " + ground);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * In deep water with the server's edits off, a player swims to a shore it can reach (F-25c9's reflexes). The nearest
     * dry ground is a pocket sealed behind the pool's wall, three blocks off: it makes for it, gets no nearer than the
     * wall, and passes it over for a shore it can reach, eight blocks off up a step. Before, it made for the nearest
     * spot again and again (live run 69: nineteen times in ten minutes).
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "reflexesWater", tickLimit = 900)
    public void inDeepWaterItSwimsToAShoreItCanReachNotTheNearestSealedOff(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 880);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        BlockPos pocket = o.add(-2, 2, 4), shore = o.add(9, 5, 4);
        context.createTimedTaskRunner()
                .createAndAdd(() -> f.awaitReady(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Swimmer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-10, 0, -10), o.add(20, 12, 20));
                    // Rock to nine up all round; a pool nine square, water four deep, open to the sky above it.
                    for (int x = -10; x <= 20; x++) for (int z = -10; z <= 20; z++) {
                        boolean pool = x >= 0 && x <= 8 && z >= 0 && z <= 8;
                        for (int y = 0; y <= 12; y++) {
                            var state = y == 0 ? Blocks.STONE.getDefaultState()
                                    : pool ? (y <= 4 ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState())
                                    : y <= 9 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState();
                            world.setBlockState(o.add(x, y, z), state);
                        }
                    }
                    // The sealed pocket behind the west wall, and the step up out of the pool in its east wall.
                    world.setBlockState(pocket, Blocks.AIR.getDefaultState());
                    world.setBlockState(pocket.up(), Blocks.AIR.getDefaultState());
                    world.setBlockState(shore, Blocks.AIR.getDefaultState());
                    world.setBlockState(shore.up(), Blocks.AIR.getDefaultState());
                    for (int y = 6; y <= 9; y++) world.setBlockState(shore.withY(o.getY() + y), Blocks.AIR.getDefaultState());
                    var player = f.player();
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 1.5, o.getY() + 1, o.getZ() + 4.5, 0, 0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isOnGround() && !player.isTouchingWater() && player.getBlockPos().equals(shore),
                            "Out of the water on the shore it could reach: at " + player.getBlockPos().subtract(o).toShortString()
                                    + ", " + f.runtime.nativeActivityStatus(Actor.console(), f.id).work());
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertTrue(world.getBlockState(pocket).isAir() && world.getBlockState(o.add(-1, 2, 4)).isOf(Blocks.STONE),
                            "The wall to the pocket left standing, edits off");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "reflexesIdle", tickLimit = 600)
    public void anIdlePlayerStillStepsOffMagmaAndEatsWhenHungry(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 590);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Idler"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.IDLE_OBSERVING) == 1,
                            "Idle, as a new player starts");
                    context.getWorld().setBlockState(f.player().getBlockPos().down(), Blocks.MAGMA_BLOCK.getDefaultState());
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).work().startsWith("Completed native_retreat"),
                        "Idle, it stepped off the magma: " + f.runtime.nativeActivityStatus(Actor.console(), f.id).work()))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.runtimeSettings(f.id).playstyleProfile().isIdleObserving(), "Still idle");
                    f.player().getInventory().setStack(0, new ItemStack(Items.APPLE, 2));
                    f.player().getHungerManager().setFoodLevel(16);
                    f.player().getHungerManager().setSaturationLevel(0);
                })
                .createAndAdd(() -> context.assertTrue(f.player().getInventory().getStack(0).getCount() == 1
                        && f.player().getHungerManager().getFoodLevel() == 20, "Idle, it ate one apple: " + f.player().getInventory().getStack(0)))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.runtimeSettings(f.id).playstyleProfile().isIdleObserving(), "Still idle after eating");
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 1, "No more food eaten than the one");
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
