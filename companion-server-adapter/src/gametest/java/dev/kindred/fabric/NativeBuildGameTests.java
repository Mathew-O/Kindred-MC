package dev.kindred.fabric;

import dev.kindred.core.construction.Generators;
import dev.kindred.core.construction.Placement;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.TreeMap;

/**
 * A build (E-08b): the native player builds a five-by-five hut from its own blocks on open ground. It has a plank floor,
 * cobblestone walls on upright logs, a doorway, a wall torch, and a gabled roof of oak stairs facing in, over a plank
 * ceiling and ridged with slabs. Each block is placed with the look and click that give its state, and checked in that
 * state. The roof's upper courses are out of reach from the ground, so it scaffolds up with its own dirt, climbs down
 * its scaffold, and takes the scaffold away at the end.
 */
public final class NativeBuildGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildHut", tickLimit = 14000)
    public void aHutIsBuiltFromItsOwnBlocksEachInItsState(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 13500);
        var hut = Generators.hut(5, 5, 3);
        var needed = new TreeMap<String, Integer>();
        for (var b : hut.blocks()) needed.merge(Placement.item(b.spec()), 1, Integer::sum);
        BlockPos[] origin = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    int slot = 0;
                    inventory.setStack(slot++, new ItemStack(Items.BREAD, 8));
                    for (var item : needed.entrySet()) {
                        int left = item.getValue();
                        while (left > 0) {
                            int count = Math.min(64, left);
                            inventory.setStack(slot++, new ItemStack(Registries.ITEM.get(Identifier.of(item.getKey())), count));
                            left -= count;
                        }
                    }
                    inventory.setStack(slot, new ItemStack(Items.DIRT, 64));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + 2, o.getZ() + 2.5, 0, 0);
                    origin[0] = o.add(4, 2, 4);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, hut, origin[0], 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    context.assertTrue(player != null, "The player still on the server");
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : hut.placed(BuildRunner.pos(origin[0]), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    context.assertTrue(wrong == 0, wrong + " blocks not in their state: " + report);
                    for (var p : hut.placedClear(BuildRunner.pos(origin[0]), 0)) {
                        var state = world.getBlockState(BuildRunner.blockPos(p));
                        context.assertTrue(state.getCollisionShape(world, BuildRunner.blockPos(p)).isEmpty(), "Kept clear: " + p + " holds " + state);
                    }
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    var dirt = new java.util.ArrayList<String>();
                    for (BlockPos pos : BlockPos.iterate(o.add(-8, 2, -8), o.add(16, 16, 16))) {
                        if (world.getBlockState(pos).isOf(Blocks.DIRT)) dirt.add(pos.subtract(origin[0]).toShortString());
                    }
                    context.assertTrue(dirt.isEmpty(), "The scaffold taken down: dirt left at " + dirt + " from the hut's corner; the player at "
                            + (player == null ? "none" : player.getBlockPos().subtract(origin[0]).toShortString()));
                    var inventory = player.getInventory();
                    var left = new TreeMap<String, Integer>();
                    for (var item : needed.keySet()) {
                        int count = inventory.count(Registries.ITEM.get(Identifier.of(item)));
                        if (count != 0) left.put(item, count);
                    }
                    context.assertTrue(left.isEmpty(), "Every block it was given went into the hut, none came from nowhere: left " + left);
                    KindredMod.LOGGER.info("Mason: built {} at {}; dirt back in hand {}, health {}", hut.name(), origin[0].toShortString(),
                            inventory.count(Items.DIRT), player.getHealth());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
