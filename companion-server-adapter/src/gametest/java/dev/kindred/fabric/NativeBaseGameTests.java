package dev.kindred.fabric;

import dev.kindred.core.construction.Houses;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * A player's own base (M-01c). With the operator's leave and AI world edits on, a player with no goal of its own and
 * stone tools in hand chooses its first base, a cabin, sites it on the nearest flat open ground to where it stands, and
 * builds it. It holds the cabin's wood but half its logs and none of its stone: it builds what it can, then in one trip
 * cuts the logs and mines the stone the rest takes, and finishes the cabin. Then, with no iron tools, it waits.
 */
public final class NativeBaseGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "base", tickLimit = 40000)
    public void aPlayerWithStoneToolsSitesAndBuildsItsOwnCabinThenWaitsForIron(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 39500);
        var cabin = Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        BlockPos home = o.add(16, 7, 16);
        int[] trips = {-1}, stoneLeft = {0};
        // Kept as it builds: whether its cabin ever ended short and was put off to try again.
        boolean[] endedShort = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    ground(context, trunks);
                    // By day: the suite's clock runs on, and at night a player with a bed of its own sleeps (F-25c9).
                    context.getWorld().setTimeOfDay(1000);
                    for (int x = -40; x <= 72; x++) for (int z = -40; z <= 72; z++) for (int y = 0; y <= 3; y++) {
                        if (world.getBlockState(o.add(x, y, z)).isOf(Blocks.STONE)) stoneLeft[0]++;
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // Stone tools; the cabin's wood but six of its twelve logs, none of its 62 cobblestone (54 and a
                    // furnace's eight), as though gathered; a spare table to craft at; dirt to scaffold with; bread.
                    ItemStack[] held = {
                            new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_PLANKS, 64),
                            new ItemStack(Items.OAK_PLANKS, 34), new ItemStack(Items.OAK_LOG, 6), new ItemStack(Items.OAK_STAIRS, 42),
                            new ItemStack(Items.OAK_SLAB, 7), new ItemStack(Items.OAK_DOOR, 1), new ItemStack(Items.RED_BED, 1),
                            new ItemStack(Items.CRAFTING_TABLE, 2), new ItemStack(Items.CHEST, 2), new ItemStack(Items.TORCH, 1),
                            new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                    // Playing, not looking on (a new player only watches until it is set to play), with the leave for bases.
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                })
                .createAndAdd(() -> {
                    trips[0] = Math.max(trips[0], f.runtime.buildTrips(f.id));
                    if (f.runtime.baseNote(f.id).contains("ended short")) endedShort[0] = true;
                    var base = f.runtime.currentBase(f.id);
                    context.assertTrue(base.isPresent() && base.get().stage().equals("cabin") && base.get().complete(), "A cabin of its own: "
                            + base + " / " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.baseNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var base = f.runtime.currentBase(f.id).orElseThrow();
                    BlockPos origin = new BlockPos(base.x(), base.y(), base.z());
                    // Sited where it stood: the cabin's middle within a few blocks of home, its floor on the ground.
                    context.assertTrue(Math.abs(origin.getX() + 3 - home.getX()) <= 4 && Math.abs(origin.getZ() + 3 - home.getZ()) <= 4
                            && origin.getY() == home.getY(), "Sited by home: " + origin.toShortString());
                    exact(context, cabin, origin);
                    int logsLeft = 0, stone = 0;
                    for (BlockPos trunk : trunks) for (int y = 0; y < 6; y++) if (world.getBlockState(trunk.up(y)).isOf(Blocks.OAK_LOG)) logsLeft++;
                    for (int x = -40; x <= 72; x++) for (int z = -40; z <= 72; z++) for (int y = 0; y <= 3; y++) {
                        if (world.getBlockState(o.add(x, y, z)).isOf(Blocks.STONE)) stone++;
                    }
                    KindredMod.LOGGER.info("Base: a cabin at {} in {} trip(s); {} stone mined, {} of 12 logs left", origin.toShortString(), trips[0],
                            stoneLeft[0] - stone, logsLeft);
                    context.assertTrue(logsLeft < 12, "Logs cut: " + (12 - logsLeft));
                    context.assertTrue(stoneLeft[0] - stone >= 50, "Stone mined for the walls: " + (stoneLeft[0] - stone));
                    context.assertTrue(trips[0] == 1, "The shortfall fetched in one trip: " + trips[0]);
                    context.assertTrue(!endedShort[0], "Built at the first go, never ended short and tried again");
                })
                // With no iron tools, the cabin serves: no house is begun.
                .createAndAdd(() -> context.assertTrue(f.runtime.baseNote(f.id).startsWith("Iron tools first"), "Waiting for iron: " + f.runtime.baseNote(f.id)))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.buildStatus(f.id).isEmpty() && f.runtime.currentBase(f.id).orElseThrow().stage().equals("cabin"),
                            "No house begun: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.currentBase(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Ground as it lies: grass on two of dirt over four of stone, no stone in sight; two trees of six logs. The stone
     * runs on 28 blocks past the field every way, as it does in a world: a tunnel dug for stone that ran off the
     * field's edge walked out onto the open test world, where there was no stone to find, and never stopped (suite run
     * 13, on 2026-10-24).
     * <p>The whole of it is kept loaded and ticking, as a server keeps a player's surroundings: the test server keeps
     * only its tests' own chunks. A base out of loaded ground is not judged for repair, and cows out of ticking ground
     * freeze. The test framework lets every forced chunk go when the batch ends.
     */
    static void ground(TestContext context, BlockPos[] trunks) {
        var world = context.getWorld();
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        for (int cx = (o.getX() - 40) >> 4; cx <= (o.getX() + 72) >> 4; cx++) {
            for (int cz = (o.getZ() - 40) >> 4; cz <= (o.getZ() + 72) >> 4; cz++) world.setChunkForced(cx, cz, true);
        }
        for (int x = -40; x <= 72; x++) for (int z = -40; z <= 72; z++) {
            for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
        }
        for (int x = -12; x <= 44; x++) for (int z = -12; z <= 44; z++) {
            for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
            for (int y = 4; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
            world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
            for (int y = 7; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        for (BlockPos trunk : trunks) {
            for (int y = 0; y < 6; y++) world.setBlockState(trunk.up(y), Blocks.OAK_LOG.getDefaultState());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) world.setBlockState(trunk.add(dx, 5, dz), Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
            }
            world.setBlockState(trunk.up(6), Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
        }
    }

    /** Every block of the cabin at {@code origin} in its state. */
    static void exact(TestContext context, dev.kindred.core.construction.Blueprint cabin, BlockPos origin) {
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
    }
}
