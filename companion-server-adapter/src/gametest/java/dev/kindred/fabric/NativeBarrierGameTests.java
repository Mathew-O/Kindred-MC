package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Barrier blocks, which a survival player cannot see, are not ground to plan on or a height to aim at (F-22c66).
 */
public final class NativeBarrierGameTests implements FabricGameTest {
    /**
     * A hurt, hungry player on open ground with no animals in sight looks for some 28 blocks out. The corpus's
     * kindred-48005 looked toward the top of the GameTest's own barrier fence round a scene: the surface there was read as
     * the fence's top, its route climbed a tree's leaves onto the fence, and it slipped off the canopy's edge, 4 blocks
     * and a jump, to its death at 3.8 health. Staged: here a barrier fence 6 high stands where each look aims, 28 out
     * every way, with a staircase of leaves up to it. A walk ends within 3 of its goal, so a look aimed at the fence's
     * top ends on the leaves, a drop of 5 beside it. It fails once the player stands on a barrier, or on leaves with a
     * drop of 4 or more beside it.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "barrierFence", tickLimit = 1400)
    public void aHurtPlayerLookingForAnimalsNeitherAimsAtNorWalksOnABarrierFence(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        long[] start = {0};
        String[] fault = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Fenced"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // In the middle of a structure as wide as the field, as F-25c4ag's field is laid.
                    BlockPos p = context.getAbsolutePos(new BlockPos(60, 2, 60)).toImmutable();
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-32, -1, -32), p.add(32, 9, 32));
                    for (int x = -32; x <= 32; x++) for (int z = -32; z <= 32; z++) {
                        world.setBlockState(p.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 9; y++) {
                            boolean fence = Math.max(Math.abs(x), Math.abs(z)) == 28 && y <= 5;
                            world.setBlockState(p.add(x, y, z), (fence ? Blocks.BARRIER : Blocks.AIR).getDefaultState());
                        }
                    }
                    // Up to the fence each way, a staircase of leaves: as the tree beside the corpus scene's fence.
                    var leaves = Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
                    for (Direction side : Direction.Type.HORIZONTAL) {
                        for (int step = 0; step <= 5; step++) {
                            for (int y = 0; y <= step; y++) world.setBlockState(p.offset(side, 22 + step).up(y), leaves);
                        }
                    }
                    world.setTimeOfDay(1000);
                    world.getEntitiesByClass(net.minecraft.entity.passive.PassiveEntity.class, new net.minecraft.util.math.Box(p.add(-60, -4, -60).toCenterPos(),
                            p.add(60, 10, 60).toCenterPos()), animal -> true).forEach(net.minecraft.entity.Entity::discard);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(40, new ItemStack(Items.SHIELD));
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    player.setHealth(4);
                    player.getHungerManager().setFoodLevel(8);
                    player.getHungerManager().setSaturationLevel(0);
                    start[0] = world.getTime();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    context.assertTrue(player != null && player.isAlive(), "Its body, alive");
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    BlockPos feet = player.getBlockPos();
                    if (fault[0] == null && player.isOnGround()) {
                        var under = world.getBlockState(feet.down());
                        if (under.isOf(Blocks.BARRIER)) fault[0] = "On a barrier at " + feet.toShortString() + "; " + progress;
                        if (under.getBlock() instanceof LeavesBlock) {
                            for (Direction side : Direction.Type.HORIZONTAL) {
                                BlockPos beside = feet.offset(side);
                                int drop = 0;
                                while (drop < 6 && world.getBlockState(beside.down(drop + 1)).getCollisionShape(world, beside.down(drop + 1)).isEmpty()) drop++;
                                if (drop >= 4) fault[0] = "On leaves at " + feet.toShortString() + " with a drop of " + drop + " " + side.asString() + "; " + progress;
                            }
                        }
                    }
                    context.assertTrue(fault[0] == null, String.valueOf(fault[0]));
                    context.assertTrue(world.getTime() - start[0] >= 600, "Half a minute looking for animals; " + progress);
                })
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
