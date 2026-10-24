package dev.kindred.fabric;

import dev.kindred.core.construction.BuildStyle;
import dev.kindred.core.construction.Compounds;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.UUID;

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

    /**
     * The farm ward's field (M-06a): with the keep and its ward standing, a player in the ward pours a bucket of water
     * into the channel down the field's middle, then tills every cell of the field with its hoe and sows a seed on
     * each, from the margins either side, never from on the field. Every cell ends farmland with wheat on it, the
     * channel wet its length, and no seed more used than the cells sown.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "compoundField", tickLimit = 12000)
    public void withItsWardStandingAPlayerWatersTillsAndSowsItsField(TestContext context) {
        field(context, false);
    }

    /**
     * The field watered, tilled and sown; with {@code restart}, the server's runtime is shut down and started again part
     * way ({@link NativeWorkRestartGameTests}).
     */
    static void field(TestContext context, boolean restart) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, restart ? 13500 : 11500);
        var style = new BuildStyle("", "oak", "cobblestone", 0, 0);
        var keep = Compounds.keep(style);
        var ward = Compounds.farmWard(style);
        var farm = Compounds.farmWall(style);
        var field = Compounds.field(style);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        BlockPos origin = o.add(4, 7, 4);
        BlockPos corner = origin.add(field.west(), -1, field.north());
        int[] seeds = {80};
        var runner = context.createTimedTaskRunner();
        runner.createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
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
                    // The keep and its ward as they stand, built before (their own fixtures build them).
                    for (var plan : java.util.List.of(keep, ward)) {
                        for (var p : plan.placedClear(BuildRunner.pos(origin), 0)) world.setBlockState(BuildRunner.blockPos(p), Blocks.AIR.getDefaultState());
                        for (var b : plan.placed(BuildRunner.pos(origin), 0)) {
                            world.setBlockState(BuildRunner.blockPos(b.at()), state(b.spec()), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
                        }
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // A hoe, a bucket of water and seeds, as though made, filled and gathered, and bread.
                    ItemStack[] held = {new ItemStack(Items.STONE_HOE), new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.WHEAT_SEEDS, 64),
                            new ItemStack(Items.WHEAT_SEEDS, 16), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // In the ward, west of the field, where the way from the keep comes up.
                    player.refreshPositionAndAngles(origin.getX() + farm.west() + 3.5, origin.getY(), origin.getZ() + farm.wayIn() - 1.5, 0, 0);
                    world.setTimeOfDay(1000);
                    context.assertTrue(f.runtime.startField(f.source, f.id, origin) == 1, "Field started");
                });
        if (restart) {
            runner.createAndAdd(() -> {
                        var world = context.getWorld();
                        int sown = 0;
                        boolean wet = false;
                        for (int x = 0; x < field.size(); x++) for (int z = 0; z < field.size(); z++) {
                            if (world.getBlockState(corner.add(x, 1, z)).isOf(Blocks.WHEAT)) sown++;
                            if (x == field.size() / 2 && world.getFluidState(corner.add(x, 0, z)).isIn(net.minecraft.registry.tag.FluidTags.WATER)) wet = true;
                        }
                        context.assertTrue(wet && sown >= 12 && f.runtime.fieldStatus(f.id).isPresent(), "The channel wet and a dozen sown, the field not done: " + sown);
                        KindredMod.LOGGER.info("Compound: {} cells of the field sown before the restart", sown);
                        f.runtime.nativeLifecycle(f.source, "stop", f.id);
                    })
                    .createAndAdd(() -> {
                        var activity = f.runtime.nativeActivityStatus(dev.kindred.core.Actor.console(), f.id);
                        context.assertTrue((activity.phase().equals("UNLOADED") || activity.phase().equals("IDLE")) && !activity.work().startsWith("Running"),
                                "Stopped and settled before the restart: " + activity.phase() + " " + activity.work());
                        f.runtime.stop();
                    })
                    .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "The runtime shut down"))
                    .createAndAdd(() -> {
                        f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory);
                        f.runtime.start();
                    })
                    .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Started again"))
                    .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", f.id) == 1, "Reconnect requested"))
                    .createAndAdd(() -> {
                        var statuses = f.runtime.nativeStatuses(dev.kindred.core.Actor.console());
                        context.assertTrue(statuses.size() == 1 && statuses.getFirst().connected() && f.player() != null, "Reconnected");
                    })
                    .createAndAdd(() -> {
                        context.assertTrue(f.runtime.fieldStatus(f.id).isPresent(), "The field taken up again by itself");
                        context.assertTrue(f.runtime.nativeStatuses(dev.kindred.core.Actor.console()).getFirst().stopped(), "Still stopped: taking its work up does not resume it");
                        f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    });
        }
        runner.createAndAdd(() -> context.assertTrue(f.runtime.fieldStatus(f.id).isEmpty(), "Field done: " + f.runtime.fieldStatus(f.id).orElse("")
                        + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int sown = 0;
                    for (int x = 0; x < field.size(); x++) for (int z = 0; z < field.size(); z++) {
                        BlockPos at = corner.add(x, 0, z);
                        if (x == field.size() / 2) {
                            context.assertTrue(world.getFluidState(at).isIn(net.minecraft.registry.tag.FluidTags.WATER), "The channel wet at " + at.toShortString());
                            continue;
                        }
                        context.assertTrue(world.getBlockState(at).isOf(Blocks.FARMLAND), "Farmland at " + at.toShortString() + ": " + world.getBlockState(at));
                        context.assertTrue(world.getBlockState(at.up()).isOf(Blocks.WHEAT), "Wheat sown at " + at.up().toShortString() + ": " + world.getBlockState(at.up()));
                        sown++;
                    }
                    var inventory = f.player().getInventory();
                    int left = inventory.count(Items.WHEAT_SEEDS);
                    context.assertTrue(left == seeds[0] - sown, "One seed a cell: " + left + " left of " + seeds[0] + " for " + sown);
                    context.assertTrue(inventory.count(Items.BUCKET) == 1 && inventory.count(Items.WATER_BUCKET) == 0, "The bucket poured");
                    context.assertTrue(inventory.count(Items.STONE_HOE) == 1, "Its hoe kept");
                    NativeBaseGameTests.exact(context, ward, origin);
                    KindredMod.LOGGER.info("Compound: the farm ward's field watered, tilled and sown, {} cells", sown);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A walled base's third stage (M-06a): the pasture ward. With the keep and the farm ward standing, a player on the
     * open ground south of them builds the pasture ward from the blocks it holds: its own three walls, lined, sharing
     * the farm ward's south wall; its pen; the cattle's doorway built shut; its own way in under its east wall. Every
     * block of all three ends exact. Then it walks in from outside by its own way, to the front of the pen's gate.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "compoundPasture", tickLimit = 48000)
    public void withItsWardsStandingAPlayerBuildsItsPastureWardAndWalksInByItsOwnWay(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 47500);
        var style = new BuildStyle("", "oak", "cobblestone", 0, 0);
        var keep = Compounds.keep(style);
        var farm = Compounds.farmWard(style);
        var pasture = Compounds.pastureWard(style);
        var wall = Compounds.pastureWall(style);
        var pen = Compounds.pen(style);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        BlockPos origin = o.add(4, 7, 4);
        BlockPos outside = origin.add(wall.east() + 5, 0, Compounds.pastureWayIn(style)), front = origin.add(pen.x() + 3, 0, wall.south() - 2);
        boolean[] under = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(60, 24, 48));
                    for (int cx = (o.getX() - 12) >> 4; cx <= (o.getX() + 60) >> 4; cx++) {
                        for (int cz = (o.getZ() - 12) >> 4; cz <= (o.getZ() + 48) >> 4; cz++) world.setChunkForced(cx, cz, true);
                    }
                    for (int x = -12; x <= 60; x++) for (int z = -12; z <= 48; z++) {
                        for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 4; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 7; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // The keep and its farm ward as they stand, built before (their own fixtures build them).
                    for (var plan : java.util.List.of(keep, farm)) {
                        for (var p : plan.placedClear(BuildRunner.pos(origin), 0)) world.setBlockState(BuildRunner.blockPos(p), Blocks.AIR.getDefaultState());
                        for (var b : plan.placed(BuildRunner.pos(origin), 0)) {
                            world.setBlockState(BuildRunner.blockPos(b.at()), state(b.spec()), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
                        }
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The ward's bill, as though gathered and crafted, with stone tools to dig its way in, dirt to
                    // scaffold with and bread.
                    ItemStack[] bill = {
                            new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_SHOVEL),
                            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
                            new ItemStack(Items.COBBLESTONE, 27),
                            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 34),
                            new ItemStack(Items.OAK_FENCE, 23), new ItemStack(Items.OAK_FENCE_GATE, 1),
                            new ItemStack(Items.TORCH, 12), new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < bill.length; slot++) inventory.setStack(slot, bill[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // On the open ground south of where the ward goes.
                    player.refreshPositionAndAngles(origin.getX() + wall.east() + 4.5, origin.getY(), origin.getZ() + wall.south() + 4.5, 0, 0);
                    world.setTimeOfDay(1000);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, pasture, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "Built: " + f.runtime.buildStatus(f.id).orElse("")
                        + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    NativeBaseGameTests.exact(context, pasture, origin);
                    NativeBaseGameTests.exact(context, farm, origin);
                    NativeBaseGameTests.exact(context, keep, origin);
                    // From outside by its own way in, to the front of the pen's gate.
                    var player = f.player();
                    player.refreshPositionAndAngles(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5, 90, 0);
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, front, 0.5) == 1, "Walk to the pen");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Mason in the world, short of the pen");
                    var feet = player.getBlockPos();
                    under[0] |= feet.getX() == origin.getX() + wall.east() && feet.getY() == origin.getY() - 3;
                    context.assertTrue(feet.equals(front), "At the pen: at " + feet + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    context.assertTrue(under[0], "Under its east wall on its way in");
                    NativeBaseGameTests.exact(context, pasture, origin);
                    KindredMod.LOGGER.info("Compound: the pasture ward built, {} blocks, walked into by its own way under its east wall", pasture.blocks().size());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The pasture ward's cattle (M-06a): with all three wards standing, a player in the paddock south of the pasture
     * opens its doorway, leads a pair of cows in through it into the pen and breeds them, and shuts the doorway behind
     * them with the blocks it dug out of it. Both cows and a calf end in the pen with its gate shut, and every block of
     * all three wards exact, the doorway with them.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "compoundCattle", tickLimit = 9000)
    public void aPlayerBringsAPairInByThePasturesDoorwayBreedsThemAndShutsItBehindThem(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8800);
        var style = new BuildStyle("", "oak", "cobblestone", 0, 0);
        var plans = java.util.List.of(Compounds.keep(style), Compounds.farmWard(style), Compounds.pastureWard(style));
        var wall = Compounds.pastureWall(style);
        var penAt = Compounds.pen(style);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        BlockPos origin = o.add(4, 7, 4);
        BlockPos pen = origin.add(penAt.x(), 0, penAt.z()), gate = pen.add(3, 0, 6);
        UUID[] cows = new UUID[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wren"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(60, 24, 52));
                    for (int cx = (o.getX() - 12) >> 4; cx <= (o.getX() + 60) >> 4; cx++) {
                        for (int cz = (o.getZ() - 12) >> 4; cz <= (o.getZ() + 52) >> 4; cz++) world.setChunkForced(cx, cz, true);
                    }
                    for (int x = -12; x <= 60; x++) for (int z = -12; z <= 52; z++) {
                        for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 4; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 7; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // The three wards as they stand, built before (their own fixtures build them).
                    for (var plan : plans) {
                        for (var p : plan.placedClear(BuildRunner.pos(origin), 0)) world.setBlockState(BuildRunner.blockPos(p), Blocks.AIR.getDefaultState());
                        for (var b : plan.placed(BuildRunner.pos(origin), 0)) {
                            world.setBlockState(BuildRunner.blockPos(b.at()), state(b.spec()), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
                        }
                    }
                    // A paddock of stone south of the pasture, two high, so the cows wander only so far: its north side
                    // the pasture's south wall.
                    int west = wall.west() - 6, east = wall.east() + 6, south = wall.south() + 12;
                    for (int x = west; x <= east; x++) for (int z = wall.south(); z <= south; z++) {
                        boolean edge = x == west || x == east || z == south || z == wall.south() && (x < wall.west() || x > wall.east());
                        if (edge) for (int y = 0; y <= 1; y++) world.setBlockState(origin.add(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // As though made and grown: a stone pickaxe to open the doorway, eight wheat, and bread.
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.WHEAT, 8));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 16));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(gate.getX() + 4.5, origin.getY(), origin.getZ() + wall.south() + 3.5, 0, 0);
                    for (int i = 0; i < 2; i++) {
                        var cow = EntityType.COW.create(world);
                        cow.refreshPositionAndAngles(gate.getX() - 3 + 6 * i + 0.5, origin.getY(), origin.getZ() + wall.south() + 8.5, 0, 0);
                        world.spawnEntity(cow);
                        cows[i] = cow.getUuid();
                    }
                    world.setTimeOfDay(1000);
                    context.assertTrue(f.runtime.startPasture(f.source, f.id, origin, dev.kindred.core.survival.Ranch.Kind.COW) == 1, "Pasture started");
                })
                .createAndAdd(() -> {
                    var status = f.runtime.ranchStatus(f.id);
                    if (status.isEmpty()) return;
                    // Where they stand, from the pen's gate, if it runs out: the log says what it did, not where.
                    var where = new StringBuilder("; from the gate, the player at ").append(f.player().getBlockPos().subtract(gate).toShortString());
                    for (UUID cow : cows) {
                        var entity = context.getWorld().getEntity(cow);
                        where.append(", a cow at ").append(entity == null ? "none" : entity.getBlockPos().subtract(gate).toShortString());
                    }
                    context.assertTrue(false, "Pasture done: " + status.orElse("") + "; " + f.runtime.nativeLastResult(f.id) + where);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var gateState = world.getBlockState(gate);
                    context.assertTrue(gateState.isOf(Blocks.OAK_FENCE_GATE) && !gateState.get(net.minecraft.block.FenceGateBlock.OPEN), "The pen's gate shut: " + gateState);
                    var penned = world.getEntitiesByClass(CowEntity.class, new Box(pen).expand(8), c -> inPen(pen, c.getBlockPos()));
                    long adults = penned.stream().filter(c -> !c.isBaby()).count(), calves = penned.stream().filter(CowEntity::isBaby).count();
                    context.assertTrue(adults == 2 && penned.stream().filter(c -> !c.isBaby()).allMatch(c -> c.getUuid().equals(cows[0]) || c.getUuid().equals(cows[1])),
                            "Both cows penned: " + adults);
                    context.assertTrue(calves == 1, "A calf born in the pen: " + calves);
                    // Every block exact but the pen's gate, which turns to face whoever opens it: checked above as a gate, shut.
                    NativeBaseGameTests.exact(context, plans.get(0), origin);
                    NativeBaseGameTests.exact(context, plans.get(1), origin);
                    var pasture = plans.get(2);
                    var gatePlan = penAt.add(3, 0, 6);
                    NativeBaseGameTests.exact(context, new dev.kindred.core.construction.Blueprint(pasture.name(), pasture.source(), pasture.creator(), pasture.license(),
                            pasture.blocks().stream().filter(b -> !b.at().equals(gatePlan)).toList()), origin);
                    var inventory = f.player().getInventory();
                    context.assertTrue(inventory.count(Items.WHEAT) == 6, "Two wheat fed: " + inventory.count(Items.WHEAT) + " left");
                    KindredMod.LOGGER.info("Compound: a pair brought into the pasture by its doorway and bred, {} cows and {} calf penned, the doorway shut behind them",
                            adults, calves);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A walled base's fourth stage (M-06a): the hall. With the keep, the farm ward and the pasture ward standing, a
     * player on the open ground west of them builds the hall from the blocks it holds: its ward, lined, sharing the
     * keep's south wall and the pasture's west wall; the two-floor house inside it; its own way in under its west
     * wall. Then a tower at each of the compound's five outer corners, one build after another. Every block of all
     * of them ends exact. Then it walks in from outside by its way under the west wall, to the house's door.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "compoundHall", tickLimit = 160000)
    public void withItsWardsStandingAPlayerBuildsItsHallAndTowersAndWalksInByItsOwnWay(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 159500);
        var style = new BuildStyle("", "oak", "cobblestone", 0, 0);
        var standing = java.util.List.of(Compounds.keep(style), Compounds.farmWard(style), Compounds.pastureWard(style));
        var hall = Compounds.hall(style);
        var towers = Compounds.towers(style);
        var wall = Compounds.hallWall(style);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        BlockPos origin = o.add(4, 7, 4);
        BlockPos outside = origin.add(wall.west() - 6, 0, wall.wayIn());
        // In front of the house's door, in the south of the ward.
        BlockPos door = hall.blocks().stream().filter(b -> b.spec().block().endsWith("_door") && "lower".equals(b.spec().property("half")))
                .map(b -> origin.add(b.at().x(), b.at().y(), b.at().z())).findFirst().orElseThrow();
        BlockPos front = new BlockPos(door.getX(), origin.getY(), door.getZ() + 1);
        boolean[] under = {false};
        var runner = context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(60, 24, 52));
                    for (int cx = (o.getX() - 12) >> 4; cx <= (o.getX() + 60) >> 4; cx++) {
                        for (int cz = (o.getZ() - 12) >> 4; cz <= (o.getZ() + 52) >> 4; cz++) world.setChunkForced(cx, cz, true);
                    }
                    for (int x = -12; x <= 60; x++) for (int z = -12; z <= 52; z++) {
                        for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 4; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 7; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // The other three wards as they stand, built before (their own fixtures build them).
                    for (var plan : standing) {
                        for (var p : plan.placedClear(BuildRunner.pos(origin), 0)) world.setBlockState(BuildRunner.blockPos(p), Blocks.AIR.getDefaultState());
                        for (var b : plan.placed(BuildRunner.pos(origin), 0)) {
                            world.setBlockState(BuildRunner.blockPos(b.at()), state(b.spec()), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
                        }
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The bill of the hall and its towers, as though gathered and crafted, with stone tools, dirt to
                    // scaffold with and bread: 441 stone for the hall, 315 for the towers but the wall's corners.
                    var bill = new java.util.ArrayList<ItemStack>(java.util.List.of(new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_SHOVEL)));
                    for (int i = 0; i < 11; i++) bill.add(new ItemStack(Items.COBBLESTONE, 64));
                    bill.add(new ItemStack(Items.COBBLESTONE, 52));
                    for (int i = 0; i < 6; i++) bill.add(new ItemStack(Items.OAK_PLANKS, 64));
                    bill.addAll(java.util.List.of(new ItemStack(Items.OAK_PLANKS, 60), new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.OAK_STAIRS, 4),
                            new ItemStack(Items.OAK_DOOR, 1), new ItemStack(Items.RED_BED, 1), new ItemStack(Items.RED_BED, 1), new ItemStack(Items.CHEST, 5),
                            new ItemStack(Items.CRAFTING_TABLE, 1), new ItemStack(Items.FURNACE, 2), new ItemStack(Items.TORCH, 17),
                            new ItemStack(Items.DIRT, 64), new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 16)));
                    for (int slot = 0; slot < bill.size(); slot++) inventory.setStack(slot, bill.get(slot));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5, 0, 0);
                    world.setTimeOfDay(1000);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, hall, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "The hall built: " + f.runtime.buildStatus(f.id).orElse("")
                        + " / " + f.runtime.nativeLastResult(f.id)));
        // Then each tower, a build of its own, in turn.
        for (var tower : towers) {
            runner.createAndAdd(() -> context.assertTrue(f.runtime.startBuild(f.source, f.id, tower, origin, 0) == 1, "Begun: " + tower.name()))
                    .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), tower.name() + " built: "
                            + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)));
        }
        runner
                .createAndAdd(() -> {
                    NativeBaseGameTests.exact(context, hall, origin);
                    for (var tower : towers) NativeBaseGameTests.exact(context, tower, origin);
                    for (var plan : standing) NativeBaseGameTests.exact(context, plan, origin);
                    var player = f.player();
                    player.refreshPositionAndAngles(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5, 270, 0);
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, front, 0.5) == 1, "Walk to the hall's door");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Mason in the world, short of the door");
                    var feet = player.getBlockPos();
                    under[0] |= feet.getX() == origin.getX() + wall.west() && feet.getY() == origin.getY() - 3;
                    context.assertTrue(feet.equals(front), "At the door: at " + feet + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    context.assertTrue(under[0], "Under its west wall on its way in");
                    NativeBaseGameTests.exact(context, hall, origin);
                    KindredMod.LOGGER.info("Compound: the hall built, {} blocks, and its {} towers, walked into by its own way under its west wall", hall.blocks().size(),
                            towers.size());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** Within the ring of the seven-square pen whose north-west post is {@code pen}. */
    private static boolean inPen(BlockPos pen, BlockPos at) {
        return at.getX() > pen.getX() && at.getX() < pen.getX() + 6 && at.getZ() > pen.getZ() && at.getZ() < pen.getZ() + 6 && Math.abs(at.getY() - pen.getY()) <= 2;
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
