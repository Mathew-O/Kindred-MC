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
 * A build's needs as a queue (E-08c). A player builds a hut holding only part of it: its planks, some cobblestone
 * and four logs. Its own storage close by holds the torch and the rest of the cobblestone, taken at once. It builds what
 * it can, then, stuck for the roof's stairs and slabs and the corners' logs, cuts all the logs the rest takes in one
 * trip, crafts the planks, stairs and slabs, and finishes the hut.
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
}
