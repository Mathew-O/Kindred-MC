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

    /**
     * A walled base's second stage (M-06a): the farm ward. With the keep standing, a player on the open ground south of
     * it builds the ward east of the keep from the blocks it holds: its own three walls, lined, sharing the keep's east
     * wall; a way under that wall from the keep's yard and its own way in under its east wall; a channel dug and lined
     * down the middle of its field. Every block of the ward ends exact and the keep's still are. Then it walks from
     * the keep's yard into the field, under the shared wall.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "compoundFarm", tickLimit = 48000)
    public void withItsKeepStandingAPlayerBuildsItsFarmWardAndWalksInUnderTheSharedWall(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 47500);
        var style = new BuildStyle("", "oak", "cobblestone", 0, 0);
        var keep = Compounds.keep(style);
        var ward = Compounds.farmWard(style);
        var wall = Compounds.wall(style);
        var field = Compounds.field(style);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        BlockPos origin = o.add(4, 7, 4);
        BlockPos yard = origin.add(wall.west() + 6, 0, wall.wayIn()), inField = origin.add(field.west(), 0, field.north());
        boolean[] under = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(60, 24, 36));
                    for (int cx = (o.getX() - 12) >> 4; cx <= (o.getX() + 60) >> 4; cx++) {
                        for (int cz = (o.getZ() - 12) >> 4; cz <= (o.getZ() + 36) >> 4; cz++) world.setChunkForced(cx, cz, true);
                    }
                    for (int x = -12; x <= 60; x++) for (int z = -12; z <= 36; z++) {
                        for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 4; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 7; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // The keep as it stands, built before (its own fixture builds it).
                    for (var p : keep.placedClear(BuildRunner.pos(origin), 0)) world.setBlockState(BuildRunner.blockPos(p), Blocks.AIR.getDefaultState());
                    for (var b : keep.placed(BuildRunner.pos(origin), 0)) {
                        world.setBlockState(BuildRunner.blockPos(b.at()), state(b.spec()), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The ward's bill, as though gathered, with stone tools to dig its way through and its channel, dirt
                    // to scaffold with and bread.
                    ItemStack[] bill = {
                            new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_SHOVEL),
                            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
                            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 44),
                            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 40),
                            new ItemStack(Items.TORCH, 12), new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < bill.length; slot++) inventory.setStack(slot, bill[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // On the open ground south of where the ward goes: from inside the keep, its site lay beyond the
                    // keep's east wall, the way under it not yet dug, and the way round out of the keep too far to find.
                    var farm = Compounds.farmWall(style);
                    player.refreshPositionAndAngles(origin.getX() + farm.west() + 9.5, origin.getY(), origin.getZ() + farm.south() + 4.5, 0, 0);
                    world.setTimeOfDay(1000);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, ward, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "Built: " + f.runtime.buildStatus(f.id).orElse("")
                        + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    NativeBaseGameTests.exact(context, ward, origin);
                    NativeBaseGameTests.exact(context, keep, origin);
                    // From the keep's yard into the field.
                    var player = f.player();
                    player.refreshPositionAndAngles(yard.getX() + 0.5, yard.getY(), yard.getZ() + 0.5, 270, 0);
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, inField, 0.5) == 1, "Walk to the field");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Mason in the world, short of the field");
                    var feet = player.getBlockPos();
                    // Through the way under the shared wall, not out of the keep and round by the ward's own way in.
                    under[0] |= feet.getX() == origin.getX() + wall.east() && feet.getY() == origin.getY() - 3;
                    context.assertTrue(feet.equals(inField), "In the field: at " + feet + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    context.assertTrue(under[0], "Under the shared wall on its way from the keep's yard");
                    NativeBaseGameTests.exact(context, ward, origin);
                    NativeBaseGameTests.exact(context, keep, origin);
                    KindredMod.LOGGER.info("Compound: the farm ward built, {} blocks, its field reached from the keep's yard under the shared wall", ward.blocks().size());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** The block a plan's spec names, in its state. */
    private static net.minecraft.block.BlockState state(dev.kindred.core.construction.BlockSpec spec) {
        try {
            return net.minecraft.command.argument.BlockArgumentParser.block(net.minecraft.registry.Registries.BLOCK.getReadOnlyWrapper(), spec.toString(), false)
                    .blockState();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
