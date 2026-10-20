package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Pathfinding travel and recorded roaming breaks with real native physics, timing and pickup. */
public final class NativeNavigationGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void gotoRoutesAroundAWallAndStepsUpOntoTheGoal(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos goal = context.getAbsolutePos(new BlockPos(6, 3, 1));
        Vec3d[] start = new Vec3d[1];
        int[] startAge = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Pathfinder"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing on the fixture floor"))
                .createAndAdd(() -> {
                    for (int z = 0; z <= 5; z++) for (int y = 2; y <= 3; y++) context.setBlockState(5, y, z, Blocks.STONE);
                    context.setBlockState(6, 2, 1, Blocks.STONE);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    start[0] = f.player().getPos();
                    startAge[0] = f.player().age;
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, goal, 0.5) == 1, "Route requested");
                    context.assertTrue(f.player().getPos().equals(start[0]), "A request never teleports the body");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_goto"),
                            "Goto completes: " + activity + " at " + f.player().getPos());
                })
                .createAndAdd(() -> {
                    Vec3d at = f.player().getPos();
                    context.assertTrue(Math.hypot(at.x - (goal.getX() + 0.5), at.z - (goal.getZ() + 0.5)) < 0.8 && Math.abs(at.y - goal.getY()) < 0.1,
                            "Arrived on top of the step at " + at);
                    context.assertTrue(f.player().age - startAge[0] >= 20, "Travel took real ticks of native movement");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void breakUsesTheOwnedPickaxeAndPicksUpItsOwnDrop(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos stone = context.getAbsolutePos(new BlockPos(5, 2, 3));
        BlockPos guarded = context.getAbsolutePos(new BlockPos(3, 2, 6));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Breaker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(stone, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(guarded, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(guarded.up(), Blocks.LAVA.getDefaultState());
                    f.player().getInventory().setStack(15, new ItemStack(Items.WOODEN_PICKAXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeBreak(f.source, f.id, guarded) == 0, "A break that would release lava is refused");
                    context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("UNLOADED"),
                            "The refusal reserved no activity");
                    context.assertTrue(f.runtime.nativeBreak(f.source, f.id, stone) == 1, "Stone break requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_break"),
                            "Break completes: " + activity);
                })
                .createAndAdd(() -> {
                    context.assertTrue(context.getWorld().getBlockState(stone).isAir(), "The stone was broken");
                    context.assertTrue(context.getWorld().getBlockState(guarded).isOf(Blocks.STONE), "The lava-guarded stone is intact");
                    int cobble = ItemMoves.count(f.player().getInventory(), Items.COBBLESTONE);
                    context.assertTrue(cobble == 1, "Its own cobblestone drop was picked up: " + cobble);
                    int pickSlot = -1;
                    for (int s = 0; s < 9; s++) if (f.player().getInventory().getStack(s).isOf(Items.WOODEN_PICKAXE)) pickSlot = s;
                    context.assertTrue(pickSlot >= 0, "The owned pickaxe was moved into the hotbar to mine");
                    context.assertTrue(f.player().getInventory().getStack(pickSlot).getDamage() == 1, "Vanilla tool wear applied");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.kind().equals("native_break") && action.state() == ActionRecord.State.VERIFIED,
                            "Durable ledger records a verified break");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A block over a five-deep shaft: its drop falls to the bottom, and the pickup does not walk over the edge
     * after it. The break still completes and is recorded; the drop stays where it fell.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void breakPickupDoesNotFollowADropDownAShaft(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] feet = new BlockPos[1];
        BlockPos[] target = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Edgewise"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Absolute positions around wherever the player stood up, so the test's rotation does not matter.
                    var world = context.getWorld();
                    feet[0] = f.player().getBlockPos().toImmutable();
                    target[0] = feet[0].east();
                    world.setBlockState(target[0], Blocks.DIRT.getDefaultState());
                    for (int k = 1; k <= 5; k++) world.setBlockState(target[0].down(k), Blocks.AIR.getDefaultState());
                    world.setBlockState(target[0].down(6), Blocks.STONE.getDefaultState());
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeBreak(f.source, f.id, target[0]) == 1, "Break requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_break"),
                            "Break completes: " + activity);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(context.getWorld().getBlockState(target[0]).isAir(), "The dirt was broken");
                    context.assertTrue(player.isAlive() && player.getHealth() >= 20 && player.getBlockY() == feet[0].getY(),
                            "Still on the edge and unhurt: " + player.getBlockPos() + " health " + player.getHealth());
                    context.assertTrue(ItemMoves.count(player.getInventory(), Items.DIRT) == 0, "The drop was left at the bottom");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.kind().equals("native_break") && action.state() == ActionRecord.State.VERIFIED,
                            "Durable ledger records a verified break: " + action);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
