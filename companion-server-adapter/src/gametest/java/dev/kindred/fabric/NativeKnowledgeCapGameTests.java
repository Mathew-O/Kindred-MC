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
 * Technical: WorldKnowledge's per-kind cap. In recap-factions110-b663fb5 (seed 110 Factions on main b663fb5) six of the
 * ten "its gather found no logs" lines read "0 known" with 42 to 82 logs within 8 blocks, and Kindred's sweep had passed
 * some 10,900 logs on its way there (RUNNER 2, offline in the kept world). STAGED: open grass under the sky, the player with
 * only bread and the portal goal. Once its own first sweep round has run, its log memory is filled to the cap with real
 * logs, 2,000 oak logs set 110 to 129 blocks east, past the gather's reach (64) and its look at trees on the skyline (96),
 * standing in for the logs it saw earlier in play; then a jungle log three high is set four blocks east, a tree it comes
 * on with its memory full, as in the run. Nothing is given. Passes once it holds wood; each second it logs how many logs
 * it knows and whether it knows the tree's. Two earlier stagings did not hold the state they described, and are not
 * reproductions: memory filled before the first sweep was wiped by it (3 of 3 passed with no fix), and logs written where
 * none stood were dropped as gone within 20 ticks once something read the world there.
 */
public final class NativeKnowledgeCapGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "knowledgeCap", tickLimit = 2400)
    public void stagedWithItsLogMemoryFullOfFarTreesItStillCutsTheTreeBesideIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] feet = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Forester"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    feet[0] = p;
                    FixtureGround.keep(context, p.add(-10, -2, -10), p.add(130, 10, 10));
                    for (BlockPos c : BlockPos.iterate(p.add(-10, -2, -10), p.add(10, 8, 10))) {
                        int dy = c.getY() - p.getY();
                        world.setBlockState(c, (dy < -1 ? Blocks.DIRT : dy == -1 ? Blocks.GRASS_BLOCK : Blocks.AIR).getDefaultState());
                    }
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, -90, 0);
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                // Its own first sweep round done, so what is written into its memory now stays there.
                .createAndAdd(() -> context.assertTrue(f.runtime.campaignForTest(f.id).knowledge().sweeps() >= 1, "First sweep not done"))
                .createAndAdd(() -> {
                    BlockPos p = feet[0];
                    var knowledge = f.runtime.campaignForTest(f.id).knowledge();
                    // Real logs, 2,000 of them, 110 to 129 blocks east: past the gather's reach (64) and its look at trees on the
                    // skyline (96), so never a target; written into its memory as logs seen earlier in play are.
                    var world = context.getWorld();
                    knowledge.centerAt(p.add(120, 0, 0));
                    for (int i = 0; i < WorldKnowledge.maxPerKind(); i++) {
                        BlockPos log = p.add(110 + i % 20, i / 20 % 10, -5 + i / 200);
                        world.setBlockState(log, Blocks.OAK_LOG.getDefaultState());
                        knowledge.remember(WorldKnowledge.Kind.LOG, log);
                    }
                    knowledge.centerAt(p);
                    context.assertTrue(knowledge.count(WorldKnowledge.Kind.LOG) >= WorldKnowledge.maxPerKind(), "Log memory full: " + knowledge.count(WorldKnowledge.Kind.LOG));
                    KindredMod.LOGGER.info("Knowledge cap: log memory filled at tick {}: {}", context.getTick(), knowledge.count(WorldKnowledge.Kind.LOG));
                    for (int dy = 0; dy < 3; dy++) context.getWorld().setBlockState(p.add(4, dy, 0), Blocks.JUNGLE_LOG.getDefaultState());
                })
                .createAndAdd(() -> {
                    var kn = f.runtime.campaignForTest(f.id).knowledge();
                    if (context.getTick() % 20 == 0) {
                        var all = new java.util.HashSet<>(kn.all(WorldKnowledge.Kind.LOG));
                        int tree = 0; for (int dy = 0; dy < 3; dy++) if (all.contains(feet[0].add(4, dy, 0))) tree++;
                        long far = all.stream().filter(b -> b.getX() >= feet[0].getX() + 100).count();
                        KindredMod.LOGGER.info("Knowledge cap: tick {}: {} logs known, {} of them far, {} of the tree's 3, height {} from its start, sweep {}", context.getTick(), all.size(), far, tree,
                                f.player().getBlockY() - feet[0].getY(), kn.sweeps());
                    }
                    var inventory = f.player().getInventory();
                    int wood = inventory.count(Items.JUNGLE_LOG) + inventory.count(Items.JUNGLE_PLANKS) + inventory.count(Items.CRAFTING_TABLE) + inventory.count(Items.STICK);
                    context.assertTrue(wood > 0, "No wood yet; at " + f.player().getBlockPos().subtract(feet[0]).toShortString() + ", "
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
