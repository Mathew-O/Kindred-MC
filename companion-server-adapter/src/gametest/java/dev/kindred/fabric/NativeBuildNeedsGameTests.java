package dev.kindred.fabric;

import dev.kindred.core.construction.Generators;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: in a real test world, a player told to build a small hut holds only part of
 * what it needs; more cobblestone and the torch are in its own chest nearby, and the rest must come from
 * trees. The test checks it takes from its chest first, builds all it can, fetches the missing logs in a
 * single trip, crafts what it still lacks and finishes the hut exactly to plan. A second test checks that a
 * player short of logs cuts trees, not the corner posts of a hut standing nearer.
 *
 * Technical: A build's needs as a queue (E-08c). A player builds a hut holding only part of it: its planks,
 * some cobblestone and four logs. Its own storage close by holds the torch and the rest of the cobblestone,
 * taken at once. It builds what it can, then, stuck for the roof's stairs and slabs and the corners' logs,
 * cuts all the logs the rest takes in one trip, crafts the planks, stairs and slabs, and finishes the hut.
 *
 * <p>Short of logs, a player cuts trees, never the logs of a build standing nearer (E-08d): before, a log column on
 * solid ground was a tree wherever it stood, and a player that came up short for its hut cut the corner posts out of
 * its own cabin (the night fixture, {@link NativeLifeFirstGameTests}).
 */
public final class NativeBuildNeedsGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildNeeds", tickLimit = 16000)
    public void aBuilderShortOfBlocksBuildsWhatItCanThenFetchesTheRestInOneTrip(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 15500);
        var hut = Generators.hut(5, 5, 3);
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos origin = o.add(4, 2, 4), chest = o.add(2, 2, 14);
        // Kept as it builds: its trips for the shortfall, how much stood placed as the first set out, and how much it
        // placed before taking from its storage.
        int[] trips = {-1}, placedAtTrip = {-1}, placedBeforeTake = {0};
        boolean[] logged = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // Its field and trees, and what its player builds and leaves there, outside the plot: put back as the test
                    // ends (F-22c75b), as its built twin's are.
                    FixtureGround.keep(context, o.add(-8, 1, -8), o.add(32, 16, 24));
                    for (int x = -8; x <= 32; x++) for (int z = -8; z <= 24; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // Four trees to the east, six logs each.
                    for (BlockPos trunk : new BlockPos[] {o.add(22, 2, 2), o.add(26, 2, 6), o.add(22, 2, 10), o.add(26, 2, 14)}) {
                        for (int y = 0; y < 6; y++) world.setBlockState(trunk.up(y), Blocks.OAK_LOG.getDefaultState());
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                            if (dx != 0 || dz != 0) world.setBlockState(trunk.add(dx, 5, dz), Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
                        }
                        world.setBlockState(trunk.up(6), Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
                    }
                    // Its own storage: the torch and twenty cobblestone.
                    world.setBlockState(chest, Blocks.CHEST.getDefaultState());
                    var box = (ChestBlockEntity) world.getBlockEntity(chest);
                    box.setStack(0, new ItemStack(Items.TORCH, 1));
                    box.setStack(1, new ItemStack(Items.COBBLESTONE, 20));
                    context.assertTrue(f.runtime.storageSetAt(f.source, f.id, chest) == 1, "Storage assigned");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupSaving(f.id), "Storage saved"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // Part of the hut, as though gathered: the planks, fourteen of its 34 cobblestone, four of its twelve
                    // logs, and none of its stairs, slabs or torch; a table to craft at, dirt to scaffold with, bread.
                    inventory.setStack(0, new ItemStack(Items.OAK_PLANKS, 40));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 14));
                    inventory.setStack(2, new ItemStack(Items.OAK_LOG, 4));
                    inventory.setStack(3, new ItemStack(Items.CRAFTING_TABLE, 1));
                    inventory.setStack(4, new ItemStack(Items.DIRT, 64));
                    inventory.setStack(5, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 2.5, o.getY() + 2, o.getZ() + 12.5, 0, 0);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, hut, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int placed = 0;
                    for (var b : hut.placed(BuildRunner.pos(origin), 0)) {
                        if (BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at()))).sameAs(b.spec())) placed++;
                    }
                    var box = (ChestBlockEntity) world.getBlockEntity(chest);
                    boolean stocked = false;
                    for (int slot = 0; slot < box.size(); slot++) {
                        if (box.getStack(slot).isOf(Items.TORCH) || box.getStack(slot).isOf(Items.COBBLESTONE)) stocked = true;
                    }
                    if (stocked) placedBeforeTake[0] = Math.max(placedBeforeTake[0], placed);
                    int now = f.runtime.buildTrips(f.id);
                    if (now >= 1 && placedAtTrip[0] < 0) placedAtTrip[0] = placed;
                    trips[0] = Math.max(trips[0], now);
                    context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / "
                            + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : hut.placed(BuildRunner.pos(origin), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    context.assertTrue(wrong == 0, wrong + " blocks not in their state: " + report);
                    var box = (ChestBlockEntity) world.getBlockEntity(chest);
                    int torches = 0, cobble = 0;
                    for (int slot = 0; slot < box.size(); slot++) {
                        if (box.getStack(slot).isOf(Items.TORCH)) torches += box.getStack(slot).getCount();
                        if (box.getStack(slot).isOf(Items.COBBLESTONE)) cobble += box.getStack(slot).getCount();
                    }
                    context.assertTrue(torches == 0 && cobble == 0, "The torch and cobblestone taken from its storage: " + torches + ", " + cobble + " left");
                    int logsLeft = 0;
                    for (BlockPos trunk : new BlockPos[] {o.add(22, 2, 2), o.add(26, 2, 6), o.add(22, 2, 10), o.add(26, 2, 14)}) {
                        for (int y = 0; y < 6; y++) if (world.getBlockState(trunk.up(y)).isOf(Blocks.OAK_LOG)) logsLeft++;
                    }
                    if (!logged[0]) KindredMod.LOGGER.info("Build needs: the hut built in {} trip(s), setting out with {} of 112 placed; {} placed before "
                            + "taking from its storage; {} of the trees' 24 logs left standing", trips[0], placedAtTrip[0], placedBeforeTake[0], logsLeft);
                    logged[0] = true;
                    context.assertTrue(logsLeft < 24, "Logs cut from the trees: " + (24 - logsLeft));
                    context.assertTrue(trips[0] == 1, "The shortfall fetched in one trip: " + trips[0]);
                    // Easy to get, taken at once: nothing placed while its storage close by still held the torch and cobblestone.
                    context.assertTrue(placedBeforeTake[0] == 0, "Its storage taken from first: " + placedBeforeTake[0] + " placed before");
                    // What it held, all placed before the trip: 79 of the 112 blocks are ones it had or took.
                    context.assertTrue(placedAtTrip[0] >= 79, "Built what it could before the trip: " + placedAtTrip[0] + " of 79");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A walk to a drop that ends short of it does not spend the drop (F-22c112). STAGED: a two-plank wall, no plank held, no
     * storage and no trees, so the planks can only be the two drops lying in sight. As its walk for the first (A) sets out, a
     * second (B) is laid at its feet, picked up on the way, and A moves four blocks on, so the walk ends short of it, out of
     * reach, as walks to drops were seen to (1.8 to 4.2 blocks short on the trading hall's wool). Before, that walk counted
     * as A's one walk and A was never gone for again: the wall stood a plank short, as a pasture doorway's shut did on CI.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildNeedsShortWalk", tickLimit = 2400)
    public void aWalkThatEndsShortOfADropDoesNotSpendIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos origin = o.add(4, 2, 4);
        var plank = dev.kindred.core.construction.BlockSpec.of("minecraft:oak_planks");
        var wall = new dev.kindred.core.construction.Blueprint("two-plank wall", "test", "Kindred", "Kindred's own", java.util.List.of(
                new dev.kindred.core.construction.Blueprint.Block(new dev.kindred.core.construction.Pos(0, 0, 0), plank),
                new dev.kindred.core.construction.Blueprint.Block(new dev.kindred.core.construction.Pos(1, 0, 0), plank)));
        net.minecraft.entity.ItemEntity[] a = {null};
        boolean[] staged = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-8, 1, -8), o.add(32, 16, 24));
                    for (int x = -8; x <= 32; x++) for (int z = -8; z <= 24; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 2.5, o.getY() + 2, o.getZ() + 12.5, 0, 0);
                    a[0] = new net.minecraft.entity.ItemEntity(world, o.getX() + 9.5, o.getY() + 2, o.getZ() + 12.5, new ItemStack(Items.OAK_PLANKS));
                    a[0].setVelocity(0, 0, 0);
                    world.spawnEntity(a[0]);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, wall, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> {
                    if (!staged[0] && f.runtime.buildStatus(f.id).orElse("").contains("Picking up oak planks")) {
                        // STAGED: B at its feet, taken on the way; A on four blocks, so the walk set out for it ends short.
                        var world = context.getWorld();
                        var player = f.player();
                        var b = new net.minecraft.entity.ItemEntity(world, player.getX(), player.getY(), player.getZ(), new ItemStack(Items.OAK_PLANKS));
                        b.setVelocity(0, 0, 0);
                        b.setPickupDelay(0);
                        world.spawnEntity(b);
                        a[0].setPosition(a[0].getX() + 4, a[0].getY(), a[0].getZ());
                        a[0].setVelocity(0, 0, 0);
                        staged[0] = true;
                    }
                    context.assertTrue(staged[0], "The walk for the first drop set out: " + f.runtime.buildStatus(f.id).orElse(""));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int placed = 0;
                    for (var b : wall.placed(BuildRunner.pos(origin), 0)) {
                        if (BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at()))).sameAs(b.spec())) placed++;
                    }
                    context.assertTrue(placed == 2, "Both planks placed, the second from the drop the walk ended short of: " + placed + " of 2");
                    context.assertTrue(!a[0].isAlive(), "The first drop picked up");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A hut standing a few blocks off, its corner posts of oak log the nearest logs there are, and four trees further
     * east. The player holds all its new hut takes but eight of its logs and the roof's stairs and slabs. It cuts the
     * trees for them and leaves the standing hut as it was. The standing hut is staged, put up block by block as
     * someone's build; the player is given nothing that it then places as earned.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "buildNeedsBuilt", tickLimit = 16000)
    public void aBuilderShortOfLogsCutsTreesNotTheCornerPostsOfAHutStandingNearby(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 15500);
        var hut = Generators.hut(5, 5, 3);
        BlockPos o = context.getAbsolutePos(new BlockPos(10, 0, 10));
        BlockPos origin = o.add(4, 2, 4), standing = o.add(-6, 2, 14);
        BlockPos[] trunks = {o.add(22, 2, 2), o.add(26, 2, 6), o.add(22, 2, 10), o.add(26, 2, 14)};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-8, 1, -8), o.add(32, 16, 24));
                    for (int x = -8; x <= 32; x++) for (int z = -8; z <= 24; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (BlockPos trunk : trunks) {
                        for (int y = 0; y < 6; y++) world.setBlockState(trunk.up(y), Blocks.OAK_LOG.getDefaultState());
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                            if (dx != 0 || dz != 0) world.setBlockState(trunk.add(dx, 5, dz), Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
                        }
                        world.setBlockState(trunk.up(6), Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
                    }
                    // The hut already standing, west of where the player starts: twelve logs in its four corners, each
                    // on its plank floor between cobblestone walls, with stairs over the top ones.
                    for (var b : hut.placed(BuildRunner.pos(standing), 0)) {
                        world.setBlockState(BuildRunner.blockPos(b.at()), NativeCompoundGameTests.state(b.spec()),
                                net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
                    }
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // As though gathered: the planks, all its cobblestone, four of its twelve logs and the torch; none of
                    // its stairs or slabs. A table to craft at, dirt to scaffold with, bread.
                    inventory.setStack(0, new ItemStack(Items.OAK_PLANKS, 40));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 34));
                    inventory.setStack(2, new ItemStack(Items.OAK_LOG, 4));
                    inventory.setStack(3, new ItemStack(Items.CRAFTING_TABLE, 1));
                    inventory.setStack(4, new ItemStack(Items.DIRT, 64));
                    inventory.setStack(5, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(6, new ItemStack(Items.TORCH, 1));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 2.5, o.getY() + 2, o.getZ() + 12.5, 0, 0);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, hut, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "Built: " + f.runtime.buildStatus(f.id).orElse("")
                        + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int built = 0, kept = 0, logsKept = 0;
                    for (var b : hut.placed(BuildRunner.pos(origin), 0)) {
                        if (BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at()))).sameAs(b.spec())) built++;
                    }
                    for (var b : hut.placed(BuildRunner.pos(standing), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (got.sameAs(b.spec())) {
                            kept++;
                            if (b.spec().toString().contains("_log")) logsKept++;
                        }
                    }
                    int logsLeft = 0;
                    for (BlockPos trunk : trunks) for (int y = 0; y < 6; y++) if (world.getBlockState(trunk.up(y)).isOf(Blocks.OAK_LOG)) logsLeft++;
                    KindredMod.LOGGER.info("Build needs, a hut standing by: {} of 112 built; the standing hut {} of 112 as it was, {} of its "
                            + "12 logs; {} of the trees' 24 logs left", built, kept, logsKept, logsLeft);
                    context.assertTrue(built == 112, "The new hut built: " + built + " of 112");
                    context.assertTrue(logsKept == 12 && kept == 112, "The standing hut left as it was: " + kept + " of 112, " + logsKept + " of its 12 logs");
                    context.assertTrue(logsLeft < 24, "Logs cut from the trees: " + (24 - logsLeft));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
