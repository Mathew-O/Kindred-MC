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

import java.util.List;
import java.util.TreeMap;

/**
 * A build (E-08b): the native player builds a five-by-five hut from its own blocks on open ground. It has a plank floor,
 * cobblestone walls on upright logs, a doorway, a wall torch, and a gabled roof of oak stairs facing in, over a plank
 * ceiling and ridged with slabs. Each block is placed with the look and click that give its state, and checked in that
 * state. The roof's upper courses are out of reach from the ground, so it scaffolds up with its own dirt, climbs down
 * its scaffold, and takes the scaffold away at the end.
 */
public final class NativeBuildGameTests implements FabricGameTest {
    /**
     * A check retried each tick until it passes or the test runs out: the first failure is kept as the message, so a
     * test that runs out (and stops its runtime, taking the player away) still says what was wrong.
     */
    private static void check(TestContext context, String[] first, boolean ok, String message) {
        if (ok) return;
        if (first[0] == null) first[0] = message;
        context.assertTrue(false, first[0]);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildHut", tickLimit = 14000)
    public void aHutIsBuiltFromItsOwnBlocksEachInItsState(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 13500);
        String[] first = {null};
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
                    check(context, first, player != null, "The player still on the server");
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : hut.placed(BuildRunner.pos(origin[0]), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    check(context, first, wrong == 0, wrong + " blocks not in their state: " + report);
                    for (var p : hut.placedClear(BuildRunner.pos(origin[0]), 0)) {
                        var state = world.getBlockState(BuildRunner.blockPos(p));
                        check(context, first, state.getCollisionShape(world, BuildRunner.blockPos(p)).isEmpty(), "Kept clear: " + p + " holds " + state);
                    }
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    var dirt = new java.util.ArrayList<String>();
                    for (BlockPos pos : BlockPos.iterate(o.add(-8, 2, -8), o.add(16, 16, 16))) {
                        if (world.getBlockState(pos).isOf(Blocks.DIRT)) dirt.add(pos.subtract(origin[0]).toShortString());
                    }
                    check(context, first, dirt.isEmpty(), "The scaffold taken down: dirt left at " + dirt + " from the hut's corner; the player at "
                            + (player == null ? "none" : player.getBlockPos().subtract(origin[0]).toShortString()));
                    var inventory = player.getInventory();
                    var left = new TreeMap<String, Integer>();
                    for (var item : needed.keySet()) {
                        int count = inventory.count(Registries.ITEM.get(Identifier.of(item)));
                        if (count != 0) left.put(item, count);
                    }
                    check(context, first, left.isEmpty(), "Every block it was given went into the hut, none came from nowhere: left " + left);
                    KindredMod.LOGGER.info("Mason: built {} at {}; dirt back in hand {}, health {}", hut.name(), origin[0].toShortString(),
                            inventory.count(Items.DIRT), player.getHealth());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A schematic (M-05): a small kiosk is saved by vanilla's own structure writer, as a structure block saves one, into
     * the schematic library with a license beside it. The kiosk has cobblestone and planks, logs upright and lying, glass,
     * top-half stairs and a lantern, with air kept clear. The native player builds it eight blocks off, turned a quarter
     * clockwise, from exactly the blocks it takes. Every block must match the original as vanilla's own rotation turns
     * it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildSchematic", tickLimit = 9000)
    public void aStructureSavedByVanillaIsReadAndBuiltTurnedAQuarter(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8500);
        String[] first = {null};
        var original = new java.util.LinkedHashMap<BlockPos, net.minecraft.block.BlockState>();
        BlockPos[] origin = new BlockPos[1];
        String name = "kiosk-" + Long.toHexString(context.getWorld().getTime());
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Joiner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -6; x <= 18; x++) for (int z = -6; z <= 14; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 12; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // The kiosk, at the corner: a floor ring, posts and glass, a roof of lying logs and top-half stairs, a lantern.
                    BlockPos k = o.add(0, 2, 0);
                    var cobble = Blocks.COBBLESTONE.getDefaultState();
                    var planks = Blocks.OAK_PLANKS.getDefaultState();
                    var post = Blocks.OAK_LOG.getDefaultState();
                    var beam = Blocks.OAK_LOG.getDefaultState().with(net.minecraft.state.property.Properties.AXIS, net.minecraft.util.math.Direction.Axis.X);
                    var stairs = Blocks.STONE_BRICK_STAIRS.getDefaultState()
                            .with(net.minecraft.block.StairsBlock.FACING, net.minecraft.util.math.Direction.NORTH)
                            .with(net.minecraft.block.StairsBlock.HALF, net.minecraft.block.enums.BlockHalf.TOP);
                    for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) {
                        boolean corner = (x == 0 || x == 2) && (z == 0 || z == 2);
                        if (x == 1 && z == 1) continue;
                        world.setBlockState(k.add(x, 0, z), corner ? cobble : planks);
                        if (corner) world.setBlockState(k.add(x, 1, z), post);
                    }
                    world.setBlockState(k.add(0, 1, 1), Blocks.GLASS.getDefaultState());
                    world.setBlockState(k.add(2, 1, 1), Blocks.GLASS.getDefaultState());
                    for (int x = 0; x <= 2; x++) {
                        world.setBlockState(k.add(x, 2, 0), beam);
                        world.setBlockState(k.add(x, 2, 2), beam);
                        world.setBlockState(k.add(x, 2, 1), stairs);
                    }
                    world.setBlockState(k.add(1, 3, 1), Blocks.LANTERN.getDefaultState());
                    for (BlockPos pos : BlockPos.iterate(k, k.add(2, 3, 2))) original.put(pos.subtract(k).toImmutable(), world.getBlockState(pos));
                    // Saved as a structure block saves it: air kept as air, structure void left out.
                    var template = new net.minecraft.structure.StructureTemplate();
                    template.saveFromWorld(world, k, new net.minecraft.util.math.Vec3i(3, 4, 3), false, Blocks.STRUCTURE_VOID);
                    var nbt = template.writeNbt(new net.minecraft.nbt.NbtCompound());
                    try {
                        var directory = f.runtime.schematics().directory();
                        java.nio.file.Files.createDirectories(directory);
                        try (var out = java.nio.file.Files.newOutputStream(directory.resolve(name + ".nbt"))) {
                            net.minecraft.nbt.NbtIo.writeCompressed(nbt, out);
                        }
                        java.nio.file.Files.writeString(directory.resolve(name + ".license"),
                                "creator=Kindred fixture\nlicense=CC0-1.0\nsource=saved by a structure block in the fixture\n");
                    } catch (java.io.IOException failure) {
                        throw new RuntimeException(failure);
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    var needed = new TreeMap<String, Integer>();
                    for (var state : original.values()) {
                        if (state.isAir()) continue;
                        needed.merge(Registries.ITEM.getId(state.getBlock().asItem()).toString(), 1, Integer::sum);
                    }
                    int slot = 0;
                    inventory.setStack(slot++, new ItemStack(Items.BREAD, 8));
                    for (var item : needed.entrySet()) inventory.setStack(slot++, new ItemStack(Registries.ITEM.get(Identifier.of(item.getKey())), item.getValue()));
                    inventory.setStack(slot, new ItemStack(Items.DIRT, 32));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 5.5, o.getY() + 2, o.getZ() + 9.5, 0, 0);
                    origin[0] = o.add(12, 2, 4);
                    context.assertTrue(f.runtime.startSchematicBuild(f.source, f.id, name, origin[0], 1) == 1, "Build asked for");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isPresent(), "Read and started"))
                .createAndAdd(() -> {
                    if (context.getWorld().getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Joiner waiting: {} / {} / {} / {}", f.runtime.buildStatus(f.id).orElse(""), f.runtime.nativeLastResult(f.id),
                                f.runtime.nativeActivityStatus(dev.kindred.core.Actor.console(), f.id), f.runtime.nativeStatusLine(f.id));
                    }
                    context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(), "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / "
                            + f.runtime.nativeLastResult(f.id) + " / " + f.runtime.nativeActivityStatus(dev.kindred.core.Actor.console(), f.id));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    check(context, first, player != null, "The player still on the server");
                    int wrong = 0, placed = 0;
                    var report = new StringBuilder();
                    for (var entry : original.entrySet()) {
                        var p = entry.getKey();
                        // A quarter turn clockwise seen from above, as vanilla turns a structure: x, z becomes -z, x.
                        BlockPos at = origin[0].add(-p.getZ(), p.getY(), p.getX());
                        var want = entry.getValue().rotate(net.minecraft.util.BlockRotation.CLOCKWISE_90);
                        var got = world.getBlockState(at);
                        if (want.isAir()) {
                            if (!got.getCollisionShape(world, at).isEmpty()) {
                                wrong++;
                                report.append(at.subtract(origin[0]).toShortString()).append(" not clear: ").append(got).append("; ");
                            }
                            continue;
                        }
                        placed++;
                        if (!BuildRunner.spec(got).sameAs(BuildRunner.spec(want))) {
                            wrong++;
                            if (report.length() < 600) report.append(at.subtract(origin[0]).toShortString()).append(' ').append(BuildRunner.spec(got))
                                    .append(" for ").append(BuildRunner.spec(want)).append("; ");
                        }
                    }
                    check(context, first, placed == 24 && wrong == 0, wrong + " of " + placed + " not as vanilla turns the original: " + report);
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    int dirt = 0;
                    for (BlockPos pos : BlockPos.iterate(o.add(-6, 2, -6), o.add(18, 12, 14))) if (world.getBlockState(pos).isOf(Blocks.DIRT)) dirt++;
                    check(context, first, dirt == 0, "The scaffold taken down: " + dirt + " left");
                    for (var item : List.of(Items.COBBLESTONE, Items.OAK_PLANKS, Items.OAK_LOG, Items.GLASS, Items.STONE_BRICK_STAIRS, Items.LANTERN)) {
                        check(context, first, player.getInventory().count(item) == 0, "Every block it was given went in: " + item + " left");
                    }
                    KindredMod.LOGGER.info("Joiner: built {} turned a quarter at {}", name, origin[0].toShortString());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
