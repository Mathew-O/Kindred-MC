package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.construction.BuildStyle;
import dev.kindred.core.construction.Bunkers;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * A factions bunker's first stage (M-06b): a player with a pickaxe, planks, a little cobblestone, torches and a crafting
 * table digs the hideout out of the ground under a meadow, lines it, and furnishes it: the shed with its door, the stair
 * down six blocks, and the room seven by seven, every block as planned. The stone it lines with is mostly what it dug;
 * what it lacks it makes (the door, the chests, the furnace). Then it walks from outside the shed's door down to the
 * room.
 */
public final class NativeBunkerGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bunker", tickLimit = 40000)
    public void itDigsOutLinesAndFurnishesItsHideoutUnderTheGround(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 39500);
        var hideout = Bunkers.hideout(BuildStyle.CLASSIC);
        BlockPos[] origin = new BlockPos[1];
        BlockPos o = context.getAbsolutePos(new BlockPos(10, 0, 4));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-10, 0, -8), o.add(12, 22, 24));
                    for (int cx = (o.getX() - 10) >> 4; cx <= (o.getX() + 12) >> 4; cx++) {
                        for (int cz = (o.getZ() - 8) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) world.setChunkForced(cx, cz, true);
                    }
                    // A meadow: grass on three of dirt, stone below, down past the room's floor.
                    for (int x = -10; x <= 12; x++) for (int z = -8; z <= 24; z++) {
                        for (int y = 0; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 7; y <= 9; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 10, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 11; y <= 22; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.IRON_SHOVEL));
                    inventory.setStack(2, new ItemStack(Items.OAK_PLANKS, 64));
                    inventory.setStack(3, new ItemStack(Items.OAK_PLANKS, 64));
                    inventory.setStack(4, new ItemStack(Items.OAK_PLANKS, 40));
                    inventory.setStack(5, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(6, new ItemStack(Items.TORCH, 4));
                    inventory.setStack(7, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(8, new ItemStack(Items.BREAD, 16));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // North of where the shed's door will be.
                    origin[0] = o.add(0, 11, 0);
                    player.refreshPositionAndAngles(origin[0].getX() + 1.5, origin[0].getY(), origin[0].getZ() - 3.5, 180, 0);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, hideout, origin[0], 0) == 1, "Build started");
                })
                .createAndAdd(() -> {
                    if (context.getWorld().getTime() % 400 == 0) {
                        KindredMod.LOGGER.info("Bunker fixture: {} at {}; {}", f.runtime.buildStatus(f.id).orElse("built"),
                                f.player().getBlockPos().toShortString(), f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                            "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    NativeBaseGameTests.exact(context, hideout, origin[0]);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    // From outside the door, down to the room.
                    var player = f.player();
                    player.refreshPositionAndAngles(origin[0].getX() + 1.5, origin[0].getY(), origin[0].getZ() - 3.5, 180, 0);
                })
                .createAndAdd(() -> {
                    var room = BuildRunner.blockPos(Bunkers.ROOM.add(BuildRunner.pos(origin[0])));
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, room, 1.5) == 1, "Walk down accepted");
                })
                .createAndAdd(() -> {
                    var room = BuildRunner.blockPos(Bunkers.ROOM.add(BuildRunner.pos(origin[0])));
                    context.assertTrue(f.player().getBlockPos().getSquaredDistance(room) <= 4, "In the room: " + f.player().getBlockPos().toShortString()
                            + " / " + f.runtime.nativeLastResult(f.id));
                    KindredMod.LOGGER.info("Bunker fixture: the hideout built exact, and it walked from outside the shed's door down to the room");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
