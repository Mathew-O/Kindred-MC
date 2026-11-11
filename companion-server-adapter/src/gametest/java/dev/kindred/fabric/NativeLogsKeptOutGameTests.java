package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a player stuck for logs beside a tree it has set aside says once a stuck spell why the tree's logs
 * were not for it, and the line changes nothing it does.
 *
 * Technical: the diagnostic line for seed 110's bamboo loop (F-27a; the lead's ask of 2026-11-11). STAGED: a pen of
 * bedrock five high, floor and walls, open to the sky, so no walk or dig gains ground (in the open it explored away and
 * no spell came), one jungle log three high four blocks east inside it, its three logs set aside by the fixture as a failed walk
 * would set them aside, the player with only bread, the portal goal, and a 200-tick stuck threshold so spells come in
 * seconds. Passes if a line names the logs as set aside, and there are no more lines than stuck spells.
 */
public final class NativeLogsKeptOutGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "logsKeptOut", tickLimit = 2400)
    public void stagedStuckBesideASetAsideTreeItSaysOnceASpellWhyItsLogsWereKeptOut(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        PortalRunCampaign[] campaign = new PortalRunCampaign[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Aside"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    FixtureGround.keep(context, p.add(-10, -2, -10), p.add(10, 8, 10));
                    for (BlockPos c : BlockPos.iterate(p.add(-10, -2, -10), p.add(10, 8, 10))) {
                        int dy = c.getY() - p.getY();
                        int dx = c.getX() - p.getX(), dz = c.getZ() - p.getZ();
                        boolean wall = Math.max(Math.abs(dx), Math.abs(dz)) == 6 && dy <= 4, floor = Math.max(Math.abs(dx), Math.abs(dz)) <= 6 && dy < 0;
                        world.setBlockState(c, (wall || floor ? Blocks.BEDROCK : dy < -1 ? Blocks.DIRT : dy == -1 ? Blocks.GRASS_BLOCK : Blocks.AIR).getDefaultState());
                    }
                    for (int dy = 0; dy < 3; dy++) world.setBlockState(p.add(4, dy, 0), Blocks.JUNGLE_LOG.getDefaultState());
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, -90, 0);
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    f.runtime.stuckTicksForFixtures(f.id, 200);
                    campaign[0] = f.runtime.campaignForTest(f.id);
                    long until = world.getServer().getTicks() + 1_000_000L;
                    for (int dy = 0; dy < 3; dy++) campaign[0].setAside(p.add(4, dy, 0), until);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    String last = campaign[0].logsKeptOutLastForTest();
                    context.assertTrue(last != null && last.contains("set aside until"), "No line naming the set-aside logs yet: " + last);
                })
                .createAndAdd(() -> {
                    int lines = campaign[0].logsKeptOutLinesForTest(), spells = campaign[0].stuckSpells();
                    context.assertTrue(lines >= 1 && lines <= spells, lines + " lines for " + spells + " stuck spells");
                    KindredMod.LOGGER.info("Logs kept out: {} lines for {} spells; last: {}", lines, spells, campaign[0].logsKeptOutLastForTest());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
