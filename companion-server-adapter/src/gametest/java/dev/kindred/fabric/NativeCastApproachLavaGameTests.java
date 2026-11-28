package dev.kindred.fabric;

import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * The cast's approach to its lava never puts the player's body over lava on footing that is not there yet (F-25c4ds).
 */
public final class NativeCastApproachLavaGameTests implements FabricGameTest {

    /**
     * In plain terms: an AI player whose way on is a one-block gap with lava filling it to the brim covers the lava with a
     * block put in from where it stands, and walks over it, rather than leaning out over the lava to bridge it.
     *
     * Technical: F-25c4ds, E-40 iteration 30 batch 1 (`e40a30-63b97ac`), seed 8675309 at 17:19:55: Echo, on "Climb to the
     * cave toward lava: bridge 1", was twice at once over the gap with "feet air, floor lava" before its block went in,
     * the bridge cancelled each time, and died in that lava. A bridge's own cell was the lava's: {@code native_bridge}
     * leans out over the gap to click the side of the block it stands on, so its body was over the lava before any block
     * was there. STAGED: a course twelve up, one wide, a gap at 1 whose floor cell is a lava source held in a stone basin,
     * walls either side of the gap so there is no way round; the player at the course's start with 16 cobblestone, then a
     * route to the far end. Passes if it reaches the far end and is never in the lava nor below the course; fails at once
     * if it is in the lava.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "castApproachBridge", tickLimit = 1200)
    public void aGapFilledWithLavaToItsBrimIsCoveredFromTheEdgeNotLeanedOver(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] origin = new BlockPos[1];
        double[] lowest = {Double.MAX_VALUE};
        boolean[] lava = {false};
        StringBuilder tasks = new StringBuilder();
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Brimside"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = origin[0] = f.player().getBlockPos().up(12).toImmutable();
                    FixtureGround.keep(context, o.add(-5, -4, -4), o.add(9, 5, 4));
                    for (int x = -4; x <= 8; x++) for (int z = -3; z <= 3; z++) for (int y = -3; y <= 4; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // The course, one wide, its gap at 1; the gap's floor cell lava in a basin, walls either side of the gap.
                    for (int x = -3; x <= 6; x++) if (x != 1) world.setBlockState(o.add(x, -1, 0), Blocks.STONE.getDefaultState());
                    world.setBlockState(o.add(1, -2, 0), Blocks.STONE.getDefaultState());
                    for (int z : new int[]{-1, 1}) for (int y = -1; y <= 2; y++) world.setBlockState(o.add(1, y, z), Blocks.STONE.getDefaultState());
                    world.setBlockState(o.add(1, -1, 0), Blocks.LAVA.getDefaultState());
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.COBBLESTONE, 16));
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    player.setHealth(player.getMaxHealth());
                    player.getHungerManager().setFoodLevel(20);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the course"))
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos goal = origin[0].add(4, 0, 0);
                    lowest[0] = Math.min(lowest[0], player.getY());
                    lava[0] |= player.isInLava() || player.isOnFire();
                    String[] task = f.runtime.nativeTaskForTest(f.id);
                    String now = task[0] + " " + task[1];
                    if (!task[0].isEmpty() && !now.equals(last[0]) && tasks.length() < 1500) tasks.append(now).append("; ");
                    last[0] = now;
                    context.assertTrue(!lava[0] && lowest[0] > origin[0].getY() - 0.6, "Into the gap: down to " + (lowest[0] - origin[0].getY())
                            + (lava[0] ? ", in the lava" : "") + " at " + player.getBlockPos().subtract(origin[0]).toShortString() + ": " + tasks);
                    if (player.getBlockPos().getSquaredDistance(goal) <= 1 && player.isOnGround()) return;
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("ACTIVE")) f.runtime.nativeRouteTo(f.source, f.id, goal);
                    context.assertTrue(false, "On the way: " + player.getBlockPos().subtract(origin[0]).toShortString() + " " + tasks);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Cast approach, brim: across; lowest {} relative to the course; the gap's cell {}; {}", lowest[0] - origin[0].getY(),
                            context.getWorld().getBlockState(origin[0].add(1, -1, 0)).getBlock(), tasks);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
