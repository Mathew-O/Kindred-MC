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
import net.minecraft.util.math.Vec3d;

/** The night's shelter sealed from where the player stands (F-25c4bt). */
public class NativeShelterSealGameTests implements FabricGameTest {
    /**
     * Below ground at night with no wood, standing across the line between the two cells of its pocket, the way in a
     * corridor beyond the first: it seals the way in, not the cell its own body is half in, and waits for daylight. In
     * E-40's iteration 9 (`e40a9-dd9b037`) on 404, Sol, sealing its pocket from the edge of its cell, had each block refused
     * as obstructed by its own body, and sealed the two cells by turns for 14 stuck spells (F-25c4bt). Staged: night; a
     * block of stone, the pocket carved in it one wide, two long and two high with a corridor three long on from its first
     * cell, three blocks of stone over it all; the player set on the line between the pocket's two cells; dirt and bread,
     * no wood. Passes once the corridor's mouth is sealed at feet and head and it waits for daylight.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "shelterAstride", tickLimit = 600)
    public void standingAcrossTheLineItSealsTheWayIntoItsPocketAndWaits(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] cells = new BlockPos[3];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Sealer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos feet = player.getBlockPos();
                    BlockPos a = cells[0] = feet.add(3, 0, 0);
                    BlockPos b = cells[1] = a.south();
                    cells[2] = a.north();
                    for (BlockPos c : BlockPos.iterate(a.add(-2, -1, -5), b.add(2, 5, 2))) world.setBlockState(c, Blocks.STONE.getDefaultState());
                    for (BlockPos c : new BlockPos[] {a, a.up(), b, b.up()}) world.setBlockState(c, Blocks.AIR.getDefaultState());
                    // The corridor it came in by, on from the pocket's first cell.
                    for (int n = 1; n <= 3; n++) for (int h = 0; h <= 1; h++) world.setBlockState(a.north(n).up(h), Blocks.AIR.getDefaultState());
                    world.setTimeOfDay(14000);
                    // On the line between the two cells, a little into the second.
                    player.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.05, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.DIRT, 16));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos mouth = cells[2];
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(!world.getBlockState(mouth).isAir() && !world.getBlockState(mouth.up()).isAir()
                            && progress.contains("wait for daylight"), "The way in sealed and waiting: at " + player.getPos() + ", "
                            + world.getBlockState(mouth).getBlock() + "/" + world.getBlockState(mouth.up()).getBlock() + ", " + progress);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
