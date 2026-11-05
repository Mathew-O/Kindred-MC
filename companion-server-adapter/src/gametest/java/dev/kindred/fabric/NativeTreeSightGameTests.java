package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** Trees seen far off on the way to wood (F-25c4br). */
public class NativeTreeSightGameTests implements FabricGameTest {
    /**
     * With no wood, two trees in sight whose lower trunks are gone, the logs left in each crown too high to reach from the
     * ground: it walks to each once, and having come to it and found nothing it can take, does not walk back to it from
     * afar. In E-40's iteration 8 (`e40a8-aed3e82`), on 101, Kindred had no wood and two such oaks 60 blocks apart. Its
     * look for trees on the skyline sees past its sweep's 32 blocks only, so at each oak it saw the other, and each switch
     * began the minute after which a tree not got to is let be over again: it walked between them for nine stuck spells
     * (F-25c4br). Staged: open ground; two crowns of leaves round two logs set six and seven blocks up, 54 blocks apart,
     * the player beside the first; daylight; bread. Passes if in 1,800 ticks it walks toward the far oak and never starts
     * a walk toward an oak it has walked to and come within 32 blocks of.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "twoTreeTops", tickLimit = 2800)
    public void withNoWoodItDoesNotWalkBackToATreeItCameToWhoseLogsItCouldNotReach(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] trees = new BlockPos[2];
        boolean[] walked = new boolean[2], reached = new boolean[2];
        int[] current = {-1};
        long[] start = {-1};
        String[] back = new String[1];
        var label = java.util.regex.Pattern.compile("trees seen at (-?\\d+), (-?\\d+), (-?\\d+)");
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Crownwatcher"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.panelBots(Actor.console()).getFirst().goalProgress().startsWith("Wood:"), "Planned"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().add(170, 0, 0);
                    trees[0] = p.add(4, 0, 0);
                    trees[1] = p.add(58, 0, 0);
                    FixtureGround.simulated(context, p.add(-48, 0, -48), trees[1].add(48, 0, 48));
                    FixtureGround.keep(context, p.add(-8, -2, -8), trees[1].add(8, 12, 8));
                    for (BlockPos c : BlockPos.iterate(p.add(-8, -2, -8), trees[1].add(8, 12, 8))) {
                        int dy = c.getY() - p.getY();
                        world.setBlockState(c, (dy < -1 ? Blocks.DIRT : dy == -1 ? Blocks.GRASS_BLOCK : Blocks.AIR).getDefaultState());
                    }
                    for (BlockPos tree : trees) {
                        // What is left of an oak whose trunk was cut from the ground up: two logs in its crown.
                        world.setBlockState(tree.up(6), Blocks.OAK_LOG.getDefaultState());
                        world.setBlockState(tree.up(7), Blocks.OAK_LOG.getDefaultState());
                        for (BlockPos c : BlockPos.iterate(tree.add(-2, 6, -2), tree.add(2, 9, 2))) {
                            if (world.getBlockState(c).isAir()) {
                                world.setBlockState(c, Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true));
                            }
                        }
                    }
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    start[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "Alive");
                    for (int t = 0; t < 2; t++) {
                        double dx = player.getX() - trees[t].getX() - 0.5, dz = player.getZ() - trees[t].getZ() - 0.5;
                        if (walked[t] && dx * dx + dz * dz <= 32 * 32) reached[t] = true;
                    }
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    var m = label.matcher(progress);
                    if (m.find()) {
                        BlockPos seen = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                        for (int t = 0; t < 2; t++) {
                            if (Math.abs(seen.getX() - trees[t].getX()) > 4 || Math.abs(seen.getZ() - trees[t].getZ()) > 4 || t == current[0]) continue;
                            if (reached[t] && back[0] == null) back[0] = "walked back toward oak " + t + " after coming to it, at tick " + (context.getTick() - start[0]);
                            walked[t] = true;
                            current[0] = t;
                        }
                    }
                    context.assertTrue(context.getTick() - start[0] >= 1800, "A while");
                })
                .createAndAdd(() -> {
                    context.assertTrue(walked[1], "Walked toward the far oak");
                    context.assertTrue(back[0] == null, back[0] + "; " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
