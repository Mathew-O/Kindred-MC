package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** The trip's wood from the next trees forty blocks off, its own tree cut, before its first stone (F-25c4cr). */
public final class NativeTripWoodGameTests implements FabricGameTest {
    /**
     * With its wooden pickaxe made and the tree it made it from cut, the next trees forty blocks off: the goal walks to
     * them for the trip's wood before its first cobblestone takes it below. In E-40's iteration 15 (`e40a15-dbf4e5c`) six
     * of the ten players who later climbed from deep down for logs for sticks had packed at most five steps of the trip's
     * wood. On 101 Sol cut the one tree it had walked to, made its wooden pickaxe at its foot, and dug down from there for
     * stone with the next trees it had seen forty to sixty blocks off; at Y=19 its third stone pickaxe wore out with no
     * sticks for a fourth. Staged: a stone plot, flat and open, with four trunks of five forty blocks off; the wooden
     * pickaxe, a table beside it and three planks, as though made from the tree cut. Passes once its first cobblestone is
     * in hand with the trip's wood, 28 planks' worth or more, as the sibling fixture with trees beside it asks.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tripWoodFar", tickLimit = 3000)
    public void itsOwnTreeCutTheNextFortyBlocksOffItTakesTheTripsWoodBeforeItsFirstStone(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        int[] woodAtFirstStone = {-1};
        int[] watched = {0};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Packer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    FixtureGround.keep(context, p.add(-6, -3, -8), p.add(48, 7, 8));
                    for (int x = -6; x <= 48; x++) for (int z = -8; z <= 8; z++) {
                        for (int y = -3; y <= -1; y++) world.setBlockState(p.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 7; y++) world.setBlockState(p.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // Four trunks of five forty blocks off, as many as the sibling fixture has beside the player.
                    for (int[] t : new int[][] {{40, 3}, {42, -4}, {45, 2}, {38, -2}}) {
                        for (int y = 0; y <= 4; y++) world.setBlockState(p.add(t[0], y, t[1]), Blocks.OAK_LOG.getDefaultState());
                    }
                    world.setBlockState(p.add(1, 0, 0), Blocks.CRAFTING_TABLE.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.OAK_PLANKS, 3));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    world.setTimeOfDay(1000);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var inventory = f.player().getInventory();
                    int wood = ItemMoves.count(inventory, Items.OAK_PLANKS) + 4 * ItemMoves.count(inventory, Items.OAK_LOG);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 200 == 0) seen.append(watched[0]).append(": ").append(progress).append(", wood ").append(wood).append("; ");
                    if (woodAtFirstStone[0] < 0 && ItemMoves.count(inventory, Items.COBBLESTONE) > 0) woodAtFirstStone[0] = wood;
                    String tail = seen.length() > 600 ? "..." + seen.substring(seen.length() - 600) : seen.toString();
                    context.assertTrue(woodAtFirstStone[0] >= 0, "Its first cobblestone: " + progress + "; " + tail);
                })
                .createAndAdd(() -> {
                    context.assertTrue(woodAtFirstStone[0] >= 28, "The trip's wood in hand before its first stone, in planks: " + woodAtFirstStone[0]
                            + "; " + (seen.length() > 600 ? "..." + seen.substring(seen.length() - 600) : seen));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Trip wood far: {} planks' worth at the first stone after {} ticks; {}", woodAtFirstStone[0], watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
