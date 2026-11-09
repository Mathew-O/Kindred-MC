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

/**
 * In plain terms: a GameTest of a cave seen up a wall (MOVEMENT): the player stands in a dark chamber, and a cave
 * opens three blocks up its wall, on a ledge no walk reaches. It climbs to it with blocks of its own, as a player
 * pillars up a cave wall, rather than asking again and again for a walk there.
 *
 * Technical: The cave walk's refusals in E-40's ten seeds (e40a17): 972 "Explore the cave toward iron", most with a
 * small search ending in a pocket up a cave wall. Staged: a sealed stone block with the chamber and the ledge's
 * pocket dug in it, the pocket turning a corner, the kit given as the cave fixtures give it.
 */
public final class NativeCaveLedgeGameTests implements FabricGameTest {

    /**
     * A cave three up the chamber's wall, on a ledge: the walk there is refused, then it climbs to it with its own
     * cobblestone and stands in it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1800)
    public void aCaveSeenUpTheWallIsClimbedToWithItsOwnBlocks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] origin = new BlockPos[1];
        int[] settle = new int[1];
        boolean[] reached = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Climber"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = origin[0] = f.player().getBlockPos().toImmutable();
                    // A sealed block of stone, so no daylight reaches in.
                    for (int dx = -4; dx <= 8; dx++) for (int dz = -4; dz <= 6; dz++) for (int dy = -1; dy <= 7; dy++) {
                        world.setBlockState(p.add(dx, dy, dz), Blocks.STONE.getDefaultState());
                    }
                    // The chamber: three by three, five tall, the player in its middle.
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 4; dy++) {
                        world.setBlockState(p.add(dx, dy, dz), Blocks.AIR.getDefaultState());
                    }
                    // The cave up the east wall: on a ledge three up (its floor the stone at y 2), two cells east, then
                    // round a corner south three, so what is further in is out of sight from below.
                    for (int dx = 2; dx <= 4; dx++) for (int dy = 3; dy <= 4; dy++) world.setBlockState(p.add(dx, dy, 0), Blocks.AIR.getDefaultState());
                    for (int dz = 1; dz <= 3; dz++) for (int dy = 3; dy <= 4; dy++) world.setBlockState(p.add(4, dy, dz), Blocks.AIR.getDefaultState());
                    world.setBlockState(p.add(4, 3, 4), Blocks.IRON_ORE.getDefaultState());
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settling"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 19));
                    inventory.setStack(5, new ItemStack(Items.COAL, 4));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(7, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(8, new ItemStack(Items.SHIELD));
                    f.player().setHealth(20);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos rel = player.getBlockPos().subtract(origin[0]);
                    // In the cave up the wall: east of the chamber, at the ledge's height.
                    reached[0] |= rel.getX() >= 2 && rel.getY() == 3 && player.isOnGround();
                    context.assertTrue(reached[0], "Up in the cave on the ledge: at " + rel.toShortString() + "; "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Cave ledge: reached, at {}", f.player().getBlockPos().subtract(origin[0]).toShortString());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
