package dev.kindred.fabric;

import dev.kindred.core.construction.Houses;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * A base's first stage (M-01b). A player builds a cabin from the blocks it holds: a plank floor, stone walls on log
 * corners, a gabled roof, open windows, a door, a bed, a crafting table, a furnace, two chests and a torch, every block
 * in its exact state. Then, from outside, it walks in through the door, opening it ahead and shutting it behind.
 */
public final class NativeHouseGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "house", tickLimit = 20000)
    public void anAiPlayerBuildsACabinWithItsFurnitureThenWalksInThroughItsDoor(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 19500);
        var cabin = Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos origin = o.add(4, 2, 4);
        // The door in the middle of the south wall, a step up off the ground; inside, on the floor by the bed.
        BlockPos door = origin.add(3, 1, 6), inside = origin.add(3, 1, 3);
        boolean[] opened = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (int x = -8; x <= 24; x++) for (int z = -8; z <= 24; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The cabin's bill, as though gathered, with dirt to scaffold with and bread.
                    ItemStack[] bill = {
                            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 34), new ItemStack(Items.COBBLESTONE, 54),
                            new ItemStack(Items.OAK_LOG, 12), new ItemStack(Items.OAK_STAIRS, 42), new ItemStack(Items.OAK_SLAB, 7),
                            new ItemStack(Items.OAK_DOOR, 1), new ItemStack(Items.RED_BED, 1), new ItemStack(Items.CRAFTING_TABLE, 1),
                            new ItemStack(Items.FURNACE, 1), new ItemStack(Items.CHEST, 2), new ItemStack(Items.TORCH, 1),
                            new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 8)};
                    for (int slot = 0; slot < bill.length; slot++) inventory.setStack(slot, bill[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(origin.getX() + 3.5, origin.getY(), origin.getZ() + 10.5, 180, 0);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, cabin, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "Built: " + f.runtime.buildStatus(f.id).orElse("")
                        + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : cabin.placed(BuildRunner.pos(origin), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    context.assertTrue(wrong == 0, wrong + " blocks not in their state: " + report);
                    context.assertTrue(world.getBlockState(door).isOf(Blocks.OAK_DOOR) && !world.getBlockState(door).get(DoorBlock.OPEN), "The door stands shut");
                    // Outside, south of the door, then in.
                    var player = f.player();
                    player.refreshPositionAndAngles(origin.getX() + 3.5, origin.getY(), origin.getZ() + 9.5, 0, 0);
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, inside, 0.5) == 1, "Walk in");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    if (world.getBlockState(door).isOf(Blocks.OAK_DOOR) && world.getBlockState(door).get(DoorBlock.OPEN)) opened[0] = true;
                    var feet = f.player().getBlockPos();
                    boolean in = feet.getY() == inside.getY() && feet.getX() > origin.getX() && feet.getX() < origin.getX() + 6
                            && feet.getZ() > origin.getZ() && feet.getZ() < origin.getZ() + 6;
                    context.assertTrue(in, "Inside the cabin: at " + feet + " / " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(opened[0], "The door opened for it");
                    context.assertTrue(!world.getBlockState(door).get(DoorBlock.OPEN), "The door shut behind it");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("House: the cabin built, {} blocks, and walked into through its door", cabin.blocks().size());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A base's second stage: a house of two floors, a storage wall and a workshop below and a bedroom above, built from
     * the blocks it holds, every block in its exact state; then from outside it walks in and up its own staircase to
     * the bedroom.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "house2", tickLimit = 60000)
    public void anAiPlayerBuildsATwoFloorHouseThenWalksUpItsStairsToTheBedroom(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 59500);
        var house = Houses.of(Houses.Stage.HOUSE, "oak", "cobblestone");
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos origin = o.add(4, 2, 4);
        // The door in the middle of the south wall; upstairs, the bedroom's floor clear of the beds and the stairwell.
        BlockPos door = origin.add(4, 1, 10), upstairs = origin.add(4, 6, 7);
        boolean[] opened = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (int x = -8; x <= 24; x++) for (int z = -8; z <= 28; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The house's bill, as though gathered, with dirt to scaffold with and bread. Beds do not stack.
                    ItemStack[] bill = {
                            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 2), new ItemStack(Items.COBBLESTONE, 64),
                            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 50),
                            new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.OAK_STAIRS, 4), new ItemStack(Items.OAK_DOOR, 1),
                            new ItemStack(Items.RED_BED, 1), new ItemStack(Items.RED_BED, 1), new ItemStack(Items.CRAFTING_TABLE, 1),
                            new ItemStack(Items.FURNACE, 2), new ItemStack(Items.CHEST, 5), new ItemStack(Items.TORCH, 3),
                            new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < bill.length; slot++) inventory.setStack(slot, bill[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(origin.getX() + 4.5, origin.getY(), origin.getZ() + 14.5, 180, 0);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, house, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "Built: " + f.runtime.buildStatus(f.id).orElse("")
                        + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : house.placed(BuildRunner.pos(origin), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    context.assertTrue(wrong == 0, wrong + " blocks not in their state: " + report);
                    context.assertTrue(world.getBlockState(door).isOf(Blocks.OAK_DOOR) && !world.getBlockState(door).get(DoorBlock.OPEN), "The door stands shut");
                    var player = f.player();
                    player.refreshPositionAndAngles(origin.getX() + 4.5, origin.getY(), origin.getZ() + 13.5, 0, 0);
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, upstairs, 0.5) == 1, "Walk up");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    if (world.getBlockState(door).isOf(Blocks.OAK_DOOR) && world.getBlockState(door).get(DoorBlock.OPEN)) opened[0] = true;
                    var feet = f.player().getBlockPos();
                    boolean up = feet.getY() == upstairs.getY() && feet.getX() > origin.getX() && feet.getX() < origin.getX() + 8
                            && feet.getZ() > origin.getZ() && feet.getZ() < origin.getZ() + 10;
                    context.assertTrue(up, "Upstairs in the bedroom: at " + feet + " / " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(opened[0], "The door opened for it");
                    context.assertTrue(!world.getBlockState(door).get(DoorBlock.OPEN), "The door shut behind it");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("House: the two-floor house built, {} blocks, and walked up to its bedroom", house.blocks().size());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A base's third stage: a manor of two floors on a larger plan, with a storage hall, a workshop and an enchanting room
     * below, the table ringed by bookshelves, and bedrooms and a library above, built from the blocks it holds, every
     * block in its exact state; then from outside it walks in and up to the floor above.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "house3", tickLimit = 100000)
    public void anAiPlayerBuildsAManorWithItsEnchantingRoomThenWalksUpstairs(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 99500);
        var manor = Houses.of(Houses.Stage.MANOR, "oak", "cobblestone");
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos origin = o.add(4, 2, 4);
        BlockPos door = origin.add(6, 1, 14), upstairs = origin.add(6, 6, 8), table = origin.add(5, 1, 3);
        boolean[] opened = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (int x = -8; x <= 28; x++) for (int z = -8; z <= 32; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // The manor's bill, as though gathered, with dirt to scaffold with and bread. Beds do not stack.
                    var bill = new java.util.ArrayList<ItemStack>();
                    for (int i = 0; i < 8; i++) bill.add(new ItemStack(Items.OAK_PLANKS, 64));
                    bill.add(new ItemStack(Items.OAK_PLANKS, 18));
                    for (int i = 0; i < 5; i++) bill.add(new ItemStack(Items.COBBLESTONE, 64));
                    bill.add(new ItemStack(Items.COBBLESTONE, 44));
                    for (var stack : new ItemStack[] {new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.BOOKSHELF, 20),
                            new ItemStack(Items.CHEST, 7), new ItemStack(Items.CRAFTING_TABLE, 1), new ItemStack(Items.ENCHANTING_TABLE, 1),
                            new ItemStack(Items.FURNACE, 2), new ItemStack(Items.OAK_DOOR, 1), new ItemStack(Items.OAK_STAIRS, 4),
                            new ItemStack(Items.RED_BED, 1), new ItemStack(Items.RED_BED, 1), new ItemStack(Items.RED_BED, 1),
                            new ItemStack(Items.TORCH, 3), new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 16)}) bill.add(stack);
                    for (int slot = 0; slot < bill.size(); slot++) inventory.setStack(slot, bill.get(slot));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(origin.getX() + 6.5, origin.getY(), origin.getZ() + 18.5, 180, 0);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, manor, origin, 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "Built: " + f.runtime.buildStatus(f.id).orElse("")
                        + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : manor.placed(BuildRunner.pos(origin), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    context.assertTrue(wrong == 0, wrong + " blocks not in their state: " + report);
                    // The enchanting room works: vanilla counts the shelves the table draws on (fifteen is the most it uses).
                    int shelves = 0;
                    for (BlockPos offset : net.minecraft.block.EnchantingTableBlock.POWER_PROVIDER_OFFSETS) {
                        if (net.minecraft.block.EnchantingTableBlock.canAccessPowerProvider(world, table, offset)) shelves++;
                    }
                    context.assertTrue(shelves >= 15, "The enchanting table draws on " + shelves + " shelves");
                    context.assertTrue(world.getBlockState(door).isOf(Blocks.OAK_DOOR) && !world.getBlockState(door).get(DoorBlock.OPEN), "The door stands shut");
                    var player = f.player();
                    player.refreshPositionAndAngles(origin.getX() + 6.5, origin.getY(), origin.getZ() + 17.5, 0, 0);
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, upstairs, 0.5) == 1, "Walk up");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    if (world.getBlockState(door).isOf(Blocks.OAK_DOOR) && world.getBlockState(door).get(DoorBlock.OPEN)) opened[0] = true;
                    var feet = f.player().getBlockPos();
                    boolean up = feet.getY() == upstairs.getY() && feet.getX() > origin.getX() && feet.getX() < origin.getX() + 12
                            && feet.getZ() > origin.getZ() && feet.getZ() < origin.getZ() + 14;
                    context.assertTrue(up, "Upstairs: at " + feet + " / " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(opened[0], "The door opened for it");
                    context.assertTrue(!world.getBlockState(door).get(DoorBlock.OPEN), "The door shut behind it");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("House: the manor built, {} blocks, its enchanting table ringed by its shelves, and walked up", manor.blocks().size());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
