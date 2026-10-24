package dev.kindred.fabric;

import dev.kindred.core.construction.BuildStyle;
import dev.kindred.core.construction.Compounds;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * A walled base's first stage (M-06a): the keep. A player builds its cabin inside a walled yard from the blocks it holds:
 * a stone wall four high from its foundation, lined inside with planks and lit by torches on the lining, and no gate,
 * the way in a stair down beside the west wall, a passage under it and a stair up into the yard, all lined with stone.
 * Every block ends in its exact state. Then, from outside, it walks in by the passage.
 */
public final class NativeCompoundGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "compoundKeep", tickLimit = 48000)
    public void anAiPlayerBuildsItsKeepWalledLinedAndEnteredOnlyUnderTheWallThenWalksIn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 47500);
        var style = new BuildStyle("", "oak", "cobblestone", 0, 0);
        var keep = Compounds.keep(style);
        var wall = Compounds.wall(style);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        // The yard's floor on grass two over dirt over four of stone, as most ground is.
        BlockPos origin = o.add(4, 7, 4);
        int pz = wall.wayIn();
        BlockPos outside = origin.add(wall.west() - 6, 0, pz), yard = origin.add(wall.west() + 6, 0, pz);
        int[] lowest = {Integer.MAX_VALUE};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(40, 24, 36));
                    for (int cx = (o.getX() - 12) >> 4; cx <= (o.getX() + 40) >> 4; cx++) {
                        for (int cz = (o.getZ() - 12) >> 4; cz <= (o.getZ() + 36) >> 4; cz++) world.setChunkForced(cx, cz, true);
                    }
                    for (int x = -12; x <= 40; x++) for (int z = -12; z <= 36; z++) {
                        for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 4; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 7; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The keep's bill, as though gathered, with stone tools to dig its passage, dirt to scaffold with and bread.
                    ItemStack[] bill = {
                            new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_SHOVEL),
                            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
                            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 25),
                            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 4), new ItemStack(Items.OAK_LOG, 12), new ItemStack(Items.OAK_STAIRS, 42),
                            new ItemStack(Items.OAK_SLAB, 7), new ItemStack(Items.OAK_DOOR, 1), new ItemStack(Items.RED_BED, 1),
                            new ItemStack(Items.CRAFTING_TABLE, 1), new ItemStack(Items.FURNACE, 1), new ItemStack(Items.CHEST, 2),
                            new ItemStack(Items.TORCH, 13), new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < bill.length; slot++) inventory.setStack(slot, bill[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // Outside the wall, south of it.
                    player.refreshPositionAndAngles(origin.getX() + wall.west() + 7.5, origin.getY(), origin.getZ() + wall.south() + 5.5, 180, 0);
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, keep, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "Built: " + f.runtime.buildStatus(f.id).orElse("")
                        + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    NativeBaseGameTests.exact(context, keep, origin);
                    // Outside, west of the stair down, then in.
                    var player = f.player();
                    player.refreshPositionAndAngles(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5, 90, 0);
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, yard, 0.5) == 1, "Walk in");
                })
                .createAndAdd(() -> {
                    // Its body is gone once the fixture's own deadline stops it: a failure to get in, not a crash.
                    var player = f.player();
                    context.assertTrue(player != null, "Mason in the world, short of the yard");
                    var feet = player.getBlockPos();
                    lowest[0] = Math.min(lowest[0], feet.getY());
                    context.assertTrue(feet.equals(yard), "In the yard: at " + feet + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    // In by the passage, three below the yard, and nothing of the keep broken or built on the way.
                    context.assertTrue(lowest[0] == origin.getY() - 3, "Under the wall: its feet at most " + (origin.getY() - lowest[0]) + " below the yard");
                    NativeBaseGameTests.exact(context, keep, origin);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Compound: the keep built, {} blocks, and walked into by the passage under its wall", keep.blocks().size());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
