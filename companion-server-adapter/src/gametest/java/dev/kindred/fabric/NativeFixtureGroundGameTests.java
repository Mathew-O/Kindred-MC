package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest of the ground a fixture keeps (F-22c111). Water a fixture lets run out of the box it keeps
 * is cleared as the box is put back, so no later test finds it; water that was there before the box was kept stays.
 *
 * Technical: {@link FixtureGround#restore} puts a kept box back without updating its neighbours, so fluid that ran out
 * of the box while the test ran was never told to drain. The open-water test's sea, run out past its edge and fallen
 * below it, lay over BuildBattle's walkway in the next batch (F-22c110, CI runs 44918551355 and 43782657628). Here a box
 * is kept ten up, a water source set on its floor's edge so that it runs out and falls, and the box put back a hundred
 * ticks on; every cell round the box, out to {@link FixtureGround#SHELL} and down to the world's floor, is then read.
 */
public final class NativeFixtureGroundGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "fixtureGroundFluid", tickLimit = 400)
    public void fluidThatRanOutOfItsKeptBoxIsClearedAsItIsPutBackAndWhatWasThereStays(TestContext context) {
        var world = context.getWorld();
        var server = world.getServer();
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN).up(10);
        BlockPos min = o, max = o.add(4, 3, 4);
        // Water there before the box is kept: a source in a stone cup west of it, within the shell, that cannot run.
        BlockPos before = o.add(-5, 1, 2);
        long[] laid = {-1};
        // Water there before is not this box's: another test's ground left standing (F-22c110's check counted a replay
        // scene's on #442's CI). Read before the box is kept, and only water not there then counted.
        java.util.Set<Long> already = new java.util.HashSet<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        world.setBlockState(before.add(dx, -1, dz), Blocks.STONE.getDefaultState());
                        if (dx != 0 || dz != 0) world.setBlockState(before.add(dx, 0, dz), Blocks.STONE.getDefaultState());
                    }
                    world.setBlockState(before, Blocks.WATER.getDefaultState());
                })
                .createAndAdd(() -> {
                    // Its chunks simulated: with no player near, a chunk the test server does not tick runs no fluid.
                    FixtureGround.simulated(context, min.add(-FixtureGround.SHELL, 0, -FixtureGround.SHELL), max.add(FixtureGround.SHELL, 0, FixtureGround.SHELL));
                    outside(context, min, max, before, already, true);
                    FixtureGround.keep(context, min, max);
                    for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) world.setBlockState(o.add(x, 0, z), Blocks.STONE.getDefaultState());
                    // On the floor's east edge, with nothing east of it: it runs out of the box and falls.
                    world.setBlockState(o.add(4, 1, 2), Blocks.WATER.getDefaultState());
                    laid[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    context.assertTrue(server.getTicks() - laid[0] >= 100, "A hundred ticks on");
                    int out = outside(context, min, max, before, already, false);
                    context.assertTrue(out > 0, "The water ran out of the box before it is put back: " + out + " cells");
                    FixtureGround.restore(context);
                })
                .createAndAdd(() -> {
                    int out = outside(context, min, max, before, already, false);
                    context.assertTrue(out == 0, "No water round the box once it is put back: " + out + " cells");
                    context.assertTrue(world.getFluidState(before).isStill(), "The water there before the box was kept still stands");
                    // The cup taken away, the water with it.
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 0; dy++) {
                        world.setBlockState(before.add(dx, dy, dz), Blocks.AIR.getDefaultState());
                    }
                })
                .completeIfSuccessful();
    }

    /**
     * The cells holding a fluid round the box, out to the shell and down to the world's floor, but {@code but}: when
     * {@code reading}, each added to {@code already}; otherwise those not in {@code already} counted.
     */
    private static int outside(TestContext context, BlockPos min, BlockPos max, BlockPos but, java.util.Set<Long> already, boolean reading) {
        var world = context.getWorld();
        int n = 0;
        var at = new BlockPos.Mutable();
        int s = FixtureGround.SHELL;
        for (int x = min.getX() - s; x <= max.getX() + s; x++) for (int z = min.getZ() - s; z <= max.getZ() + s; z++) {
            for (int y = world.getBottomY(); y <= max.getY(); y++) {
                if (x >= min.getX() && x <= max.getX() && y >= min.getY() && y <= max.getY() && z >= min.getZ() && z <= max.getZ()) continue;
                at.set(x, y, z);
                if (at.equals(but)) continue;
                if (world.getFluidState(at).isEmpty()) continue;
                if (reading) already.add(at.asLong());
                else if (!already.contains(at.asLong())) n++;
            }
        }
        return n;
    }
}
