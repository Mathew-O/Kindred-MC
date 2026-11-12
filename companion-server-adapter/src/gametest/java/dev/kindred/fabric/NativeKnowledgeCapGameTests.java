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

/**
 * In plain terms: a player that has walked through a big forest, and so remembers as many logs as it can, still cuts the
 * tree a few steps from it, rather than standing by it with "no logs" while it remembers trees it left far behind.
 *
 * Technical: WorldKnowledge's per-kind cap. In recap-factions110-b663fb5 (seed 110 Factions on main b663fb5) six of
 * the ten "its gather found no logs" lines read "0 known" with 42 to 82 logs within 8 blocks, and Kindred's sweep
 * had passed some 10,900 logs on its way there (RUNNER 2, offline in the kept world). STAGED: a field of grass laid
 * over the whole of its own wide structure, under the sky, the player put on it with only bread and the portal goal.
 * After its first look round, which the fixture takes, its log memory is filled to the cap with real logs, 2,000 oak
 * logs set 100 to 111 blocks east, past the gather's reach (64) and its look at trees on the skyline (96), standing
 * in for the logs it saw earlier in play; then a jungle log three high is set four blocks east, a tree it comes on
 * with its memory full, as in the run. Nothing is given, and all of it is staged before the goal starts. Passes once
 * it has cut that tree and holds wood; each second it logs how many logs it knows and whether it knows the tree's.
 * Two earlier stagings did not hold the state they described, and are not reproductions: memory filled before the
 * first sweep was wiped by it (3 of 3 passed with no fix), and logs written where none stood were dropped as gone
 * within 20 ticks once something read the world there. A third laid its ground round wherever the player came in and
 * the logs past its 80-block field: in CI the player came in two up, on what an earlier test had left, its first
 * walk out for logs took it off that raised ground with no way back up to the tree, and it never cut it (#219's
 * first CI run). On this field with no fix it walked out to the far logs instead and cut those (3 of 3), so it
 * passes only on the tree beside it.
 */
public final class NativeKnowledgeCapGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "knowledgeCap", tickLimit = 2400)
    public void stagedWithItsLogMemoryFullOfFarTreesItStillCutsTheTreeBesideIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        // All of it inside its own structure (120 by 120): the field over the whole of it, the player by its west edge, the
        // far logs by its east edge, so nothing it lays lies over a test beside it, and nothing left there lies under it.
        BlockPos corner = context.getAbsolutePos(BlockPos.ORIGIN), top = context.getAbsolutePos(new BlockPos(119, 20, 119));
        BlockPos[] feet = {context.getAbsolutePos(new BlockPos(8, 7, 60))};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Forester"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = feet[0];
                    FixtureGround.keep(context, corner, top);
                    for (BlockPos c : BlockPos.iterate(corner, top)) {
                        int y = c.getY() - corner.getY();
                        world.setBlockState(c, (y < 4 ? Blocks.STONE : y < 6 ? Blocks.DIRT : y == 6 ? Blocks.GRASS_BLOCK : Blocks.AIR).getDefaultState(), net.minecraft.block.Block.NOTIFY_LISTENERS);
                    }
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, -90, 0);
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    // Its first look round, taken here before the goal starts: the first one empties its memory (a new
                    // dimension to it), so what is written into its memory after it stays there.
                    f.runtime.campaignForTest(f.id).knowledge().scan(f.player());
                })
                // The whole scene staged before the goal starts, so it never acts on a field with no tree in it.
                .createAndAdd(() -> {
                    BlockPos p = feet[0];
                    var knowledge = f.runtime.campaignForTest(f.id).knowledge();
                    // Real logs, 2,000 of them, 100 to 111 blocks east: past the gather's reach (64) and its look at trees on the
                    // skyline (96), so never a target; written into its memory as logs seen earlier in play are.
                    var world = context.getWorld();
                    knowledge.centerAt(p.add(105, 0, 0));
                    for (int i = 0; i < WorldKnowledge.maxPerKind(); i++) {
                        BlockPos log = p.add(100 + i % 12, i / 12 % 10, -8 + i / 120);
                        world.setBlockState(log, Blocks.OAK_LOG.getDefaultState());
                        knowledge.remember(WorldKnowledge.Kind.LOG, log);
                    }
                    knowledge.centerAt(p);
                    context.assertTrue(knowledge.count(WorldKnowledge.Kind.LOG) >= WorldKnowledge.maxPerKind(), "Log memory full: " + knowledge.count(WorldKnowledge.Kind.LOG));
                    KindredMod.LOGGER.info("Knowledge cap: log memory filled at tick {}: {}", context.getTick(), knowledge.count(WorldKnowledge.Kind.LOG));
                    for (int dy = 0; dy < 3; dy++) context.getWorld().setBlockState(p.add(4, dy, 0), Blocks.JUNGLE_LOG.getDefaultState());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var kn = f.runtime.campaignForTest(f.id).knowledge();
                    if (context.getTick() % 20 == 0) {
                        var all = new java.util.HashSet<>(kn.all(WorldKnowledge.Kind.LOG));
                        int tree = 0; for (int dy = 0; dy < 3; dy++) if (all.contains(feet[0].add(4, dy, 0))) tree++;
                        long far = all.stream().filter(b -> b.getX() >= feet[0].getX() + 90).count();
                        KindredMod.LOGGER.info("Knowledge cap: tick {}: {} logs known, {} of them far, {} of the tree's 3, height {} from its start, sweep {}", context.getTick(), all.size(), far, tree,
                                f.player().getBlockY() - feet[0].getY(), kn.sweeps());
                    }
                    var inventory = f.player().getInventory();
                    int wood = inventory.count(Items.JUNGLE_LOG) + inventory.count(Items.JUNGLE_PLANKS) + inventory.count(Items.CRAFTING_TABLE) + inventory.count(Items.STICK);
                    // The tree beside it cut, not wood from the far logs: with its memory full and no fix, it walked the 100
                    // blocks out to those instead (3 of 3 on this field), which says nothing about the tree it came on.
                    boolean treeCut = false;
                    for (int dy = 0; dy < 3; dy++) treeCut |= !context.getWorld().getBlockState(feet[0].add(4, dy, 0)).isOf(Blocks.JUNGLE_LOG);
                    context.assertTrue(treeCut && wood > 0, "Tree beside it not cut (wood " + wood + "); at " + f.player().getBlockPos().subtract(feet[0]).toShortString() + ", "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Knowledge cap: wood cut beside it with its log memory full of far trees");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
