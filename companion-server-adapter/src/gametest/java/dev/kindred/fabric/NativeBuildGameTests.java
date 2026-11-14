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
 * In plain terms: a GameTest: in a real test world, a player given exactly the blocks for a small hut is
 * checked to build it with every block placed and facing the right way, using its own dirt as scaffolding to
 * reach the roof and taking the scaffolding down afterwards, with every block it was given ending up in the
 * hut. A second test saves a small kiosk as a schematic, with its license file, and checks the player builds
 * it turned a quarter, every block matching the original as the game itself would turn it.
 *
 * Technical: A build (E-08b): the native player builds a five-by-five hut from its own blocks on open ground.
 * It has a plank floor, cobblestone walls on upright logs, a doorway, a wall torch, and a gabled roof of oak
 * stairs facing in, over a plank ceiling and ridged with slabs. Each block is placed with the look and click
 * that give its state, and checked in that state. The roof's upper courses are out of reach from the ground,
 * so it scaffolds up with its own dirt, climbs down its scaffold, and takes the scaffold away at the end.
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

    /**
     * A floor of nine planks with a bed on it, a cow standing still in the cell of the plank under the bed's foot until
     * that plank's placement has been refused three times, and then gone (E-70a1). The plank is set aside for the time
     * being; the bed, with nothing else to stand on, waits on it, the plank is tried again and the bed goes on it, and
     * the floor is whole with no scaffold left standing. In CI (#239, #240) the bed was propped in the plank's cell:
     * walled in by the rest of the floor under the bed, the prop was out of reach to take down and the cabin was left
     * a plank short.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildBedOverSetAside", tickLimit = 6000)
    public void aBedOverAPlankAnAnimalStoodInWaitsForThePlankAndTheFloorIsWhole(TestContext context) {
        java.util.concurrent.atomic.AtomicInteger refusedThere = new java.util.concurrent.atomic.AtomicInteger();
        BlockPos[] origin = new BlockPos[1];
        String[] under = {null};
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> new dev.kindred.core.ActionAdmission.Writer() {
            @Override public java.util.concurrent.CompletableFuture<dev.kindred.core.ActionRecord> prepare(dev.kindred.core.ActionRecord intent) {
                return writer.prepare(intent);
            }
            @Override public java.util.concurrent.CompletableFuture<dev.kindred.core.ActionRecord> transition(dev.kindred.core.ActionRecord next, long expectedRevision) {
                if ("native_place".equals(next.kind()) && under[0] != null && next.preconditions().contains("pos=" + under[0] + ";")
                        && next.state() == dev.kindred.core.ActionRecord.State.FAILED) refusedThere.incrementAndGet();
                return writer.transition(next, expectedRevision);
            }
        }, true, 5500);
        String[] first = {null};
        var blocks = new java.util.ArrayList<dev.kindred.core.construction.Blueprint.Block>();
        for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) {
            blocks.add(new dev.kindred.core.construction.Blueprint.Block(new dev.kindred.core.construction.Pos(x, 0, z),
                    dev.kindred.core.construction.BlockSpec.of("minecraft:oak_planks")));
        }
        blocks.add(new dev.kindred.core.construction.Blueprint.Block(new dev.kindred.core.construction.Pos(1, 1, 1),
                dev.kindred.core.construction.BlockSpec.parse("minecraft:red_bed[facing=north,part=foot]")));
        blocks.add(new dev.kindred.core.construction.Blueprint.Block(new dev.kindred.core.construction.Pos(1, 1, 0),
                dev.kindred.core.construction.BlockSpec.parse("minecraft:red_bed[facing=north,part=head]")));
        var floor = new dev.kindred.core.construction.Blueprint("bed over a set-aside plank", "test", "", "", blocks, List.of());
        net.minecraft.entity.passive.CowEntity[] cow = new net.minecraft.entity.passive.CowEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bedmaker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -8; x <= 12; x++) for (int z = -8; z <= 12; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 10; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(1, new ItemStack(Items.OAK_PLANKS, 9));
                    inventory.setStack(2, new ItemStack(Items.RED_BED, 1));
                    inventory.setStack(3, new ItemStack(Items.DIRT, 32));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                    origin[0] = o.add(4, 2, 4);
                    // The cow in the cell of the plank under the bed's foot, standing still.
                    BlockPos cell = origin[0].add(1, 0, 1);
                    under[0] = cell.toShortString();
                    cow[0] = new net.minecraft.entity.passive.CowEntity(net.minecraft.entity.EntityType.COW, world);
                    cow[0].refreshPositionAndAngles(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 0, 0);
                    cow[0].setAiDisabled(true);
                    cow[0].setPersistent();
                    world.spawnEntity(cow[0]);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, floor, origin[0], 0) == 1, "Build started");
                })
                .createAndAdd(() -> {
                    // Refused three times, the plank is set aside; the cow wanders off.
                    if (cow[0] != null && cow[0].isAlive() && refusedThere.get() >= 3) cow[0].discard();
                    context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                            "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / refused there " + refusedThere.get());
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    check(context, first, refusedThere.get() >= 3, "The plank refused while the cow stood there: " + refusedThere.get());
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : floor.placed(BuildRunner.pos(origin[0]), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    check(context, first, wrong == 0, wrong + " blocks not in their state: " + report);
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    var dirt = new java.util.ArrayList<String>();
                    for (BlockPos pos : BlockPos.iterate(o.add(-8, 2, -8), o.add(12, 10, 12))) {
                        if (world.getBlockState(pos).isOf(Blocks.DIRT)) dirt.add(pos.subtract(origin[0]).toShortString());
                    }
                    check(context, first, dirt.isEmpty(), "No scaffold left standing: dirt at " + dirt + " from the floor's corner");
                    KindredMod.LOGGER.info("Bedmaker: the floor and its bed built, the plank refused {} times while the cow stood there", refusedThere.get());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
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
        kiosk(context, false);
    }

    /**
     * A Litematica schematic (M-05): the same kiosk written as Litematica writes a {@code .litematic}, one region selected
     * backwards (its position the far corner, its sizes negative), its palette of block states as the game writes them,
     * and the indices packed three bits a block (eight states), running over from one long into the next. The native player builds it
     * as it builds the structure file, turned a quarter, and every block must match the original as vanilla turns it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildLitematic", tickLimit = 9000)
    public void aLitematicaSchematicIsReadAndBuiltTurnedAQuarter(TestContext context) {
        kiosk(context, true);
    }

    /** The kiosk, saved as a structure file or as a Litematica schematic, then read from the library and built. */
    private static void kiosk(TestContext context, boolean litematic) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8500);
        String[] first = {null};
        var original = new java.util.LinkedHashMap<BlockPos, net.minecraft.block.BlockState>();
        BlockPos[] origin = new BlockPos[1];
        String name = (litematic ? "kiosk-litematic-" : "kiosk-") + Long.toHexString(context.getWorld().getTime());
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Joiner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    // Its ground put back as it found it when the test ends, passed or failed (F-22c75a): the kiosk and the
                    // copy it builds stand past this test's own plot, oak logs among them, and a later batch's plot beside
                    // them saw them. The pit fixture's player walked to the copy's posts, seven blocks off, for a log, and
                    // missed the climb it is tested on.
                    FixtureGround.keep(context, o.add(-6, 1, -6), o.add(18, 12, 14));
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
                    net.minecraft.nbt.NbtCompound nbt;
                    if (litematic) {
                        nbt = litematic(original);
                    } else {
                        var template = new net.minecraft.structure.StructureTemplate();
                        template.saveFromWorld(world, k, new net.minecraft.util.math.Vec3i(3, 4, 3), false, Blocks.STRUCTURE_VOID);
                        nbt = template.writeNbt(new net.minecraft.nbt.NbtCompound());
                    }
                    try {
                        var directory = f.runtime.schematics().directory();
                        java.nio.file.Files.createDirectories(directory);
                        try (var out = java.nio.file.Files.newOutputStream(directory.resolve(name + (litematic ? ".litematic" : ".nbt")))) {
                            net.minecraft.nbt.NbtIo.writeCompressed(nbt, out);
                        }
                        java.nio.file.Files.writeString(directory.resolve(name + ".license"),
                                "creator=Kindred fixture\nlicense=CC0-1.0\nsource=" + (litematic ? "written as a Litematica schematic" : "saved by a structure block")
                                        + " in the fixture\n");
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

    /**
     * The kiosk (3 x 4 x 3, by position from its corner) as Litematica writes it: one region whose position is its far
     * corner and whose sizes are negative, as when the selection is made from that corner; a palette of block states,
     * air first; and each block's palette index packed least significant bit first, as wide as the palette needs (two
     * bits at least), x fastest, then z, then y, an index running over into the next long where it does not fit.
     */
    private static net.minecraft.nbt.NbtCompound litematic(java.util.Map<BlockPos, net.minecraft.block.BlockState> blocks) {
        int sx = 3, sy = 4, sz = 3;
        var palette = new java.util.ArrayList<net.minecraft.block.BlockState>();
        palette.add(Blocks.AIR.getDefaultState());
        for (var state : blocks.values()) if (!palette.contains(state)) palette.add(state);
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long[] packed = new long[(sx * sy * sz * bits + 63) / 64];
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            var state = blocks.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.getDefaultState());
            long index = palette.indexOf(state);
            int first = ((y * sz + z) * sx + x) * bits;
            packed[first >>> 6] |= index << (first & 63);
            if ((first & 63) + bits > 64) packed[(first >>> 6) + 1] |= index >>> (64 - (first & 63));
        }
        var states = new net.minecraft.nbt.NbtList();
        for (var state : palette) states.add(net.minecraft.nbt.NbtHelper.fromBlockState(state));
        var region = new net.minecraft.nbt.NbtCompound();
        region.put("Position", corner(sx - 1, sy - 1, sz - 1));
        region.put("Size", corner(-sx, -sy, -sz));
        region.put("BlockStatePalette", states);
        region.putLongArray("BlockStates", packed);
        region.put("TileEntities", new net.minecraft.nbt.NbtList());
        region.put("Entities", new net.minecraft.nbt.NbtList());
        var regions = new net.minecraft.nbt.NbtCompound();
        regions.put("Kiosk", region);
        var metadata = new net.minecraft.nbt.NbtCompound();
        metadata.putString("Name", "Kiosk");
        metadata.putString("Author", "Kindred fixture");
        metadata.put("EnclosingSize", corner(sx, sy, sz));
        var root = new net.minecraft.nbt.NbtCompound();
        root.putInt("Version", 6);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", net.minecraft.SharedConstants.getGameVersion().getSaveVersion().getId());
        root.put("Metadata", metadata);
        root.put("Regions", regions);
        return root;
    }

    private static net.minecraft.nbt.NbtCompound corner(int x, int y, int z) {
        var at = new net.minecraft.nbt.NbtCompound();
        at.putInt("x", x);
        at.putInt("y", y);
        at.putInt("z", z);
        return at;
    }

    /**
     * E-08g. A wall of cobblestone nine long and six high, all built but the top block at its far end, and the player down
     * in a closed pit three deep just off the near end (staged, as a fall into a hollow beside a cabin left it in CI 9).
     * From the pit nothing of the build is in reach, nor any spot a scaffold to that block could stand on, and no walk
     * leads out: it climbs out first, then scaffolds up and places the block. Without that, its walks round to the block
     * found "no walkable route" eight times and the build stopped.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildHollow", tickLimit = 6000)
    public void aBuildBegunFromAHollowBelowItsFloorClimbsOutAndIsFinished(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5500);
        var blocks = new java.util.ArrayList<dev.kindred.core.construction.Blueprint.Block>();
        for (int x = 0; x <= 8; x++) for (int y = 0; y <= 5; y++) blocks.add(new dev.kindred.core.construction.Blueprint.Block(
                new dev.kindred.core.construction.Pos(x, y, 0), dev.kindred.core.construction.BlockSpec.parse("minecraft:cobblestone")));
        var column = new dev.kindred.core.construction.Blueprint("wall", "fixture", "Kindred", "fixture", blocks);
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
                        for (int y = -3; y <= 1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    origin[0] = o.add(4, 2, 4);
                    for (int x = 0; x <= 8; x++) for (int y = 0; y <= 5; y++) {
                        if (x == 8 && y == 5) continue; // The block left to place.
                        world.setBlockState(origin[0].add(x, y, 0), Blocks.COBBLESTONE.getDefaultState());
                    }
                    // The pit, two by two and three deep, its near side three blocks west of the wall's near end (within
                    // the four round the site where it works from a hollow), twelve from the block left; its sides stone.
                    BlockPos pit = origin[0].add(-4, -3, 0);
                    for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) for (int y = 0; y <= 2; y++)
                        world.setBlockState(pit.add(dx, y, dz), Blocks.AIR.getDefaultState());
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 1));
                    inventory.setStack(2, new ItemStack(Items.DIRT, 64));
                    inventory.setStack(3, new ItemStack(Items.STONE_PICKAXE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // Staged: down in the pit.
                    player.refreshPositionAndAngles(pit.getX() + 0.5, pit.getY(), pit.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockY() == origin[0].getY() - 3, "In the pit: " + f.player().getBlockPos()))
                .createAndAdd(() -> context.assertTrue(f.runtime.startBuild(f.source, f.id, column, origin[0], 0) == 1, "Build started"))
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertTrue(world.getBlockState(origin[0].add(8, 5, 0)).isOf(Blocks.COBBLESTONE),
                            "The top placed; the last word: " + f.runtime.nativeLastResult(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * As the last, the wall nine high and its top block placed from its own scaffold and the scaffold coming down, and the player then
     * fallen into the pit: from there no spot to break the scaffold's top from is in reach, and its walk round found none.
     * On #86's CI (run 42566156809) a cabin's builder taking its scaffold down fell three blocks off the field's edge and
     * the take-down was "out of reach" eight times in a row, the build stopped, never climbing out. Staged: the player put
     * in the pit once its scaffold has started coming down. It climbs out and takes the rest of its scaffold down.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildHollowTakeDown", tickLimit = 8000)
    public void aScaffoldTakenDownFromAHollowBelowTheFloorIsClimbedOutOfAndTakenDown(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 7500);
        var blocks = new java.util.ArrayList<dev.kindred.core.construction.Blueprint.Block>();
        for (int x = 0; x <= 8; x++) for (int y = 0; y <= 8; y++) blocks.add(new dev.kindred.core.construction.Blueprint.Block(
                new dev.kindred.core.construction.Pos(x, y, 0), dev.kindred.core.construction.BlockSpec.parse("minecraft:cobblestone")));
        var column = new dev.kindred.core.construction.Blueprint("wall", "fixture", "Kindred", "fixture", blocks);
        BlockPos[] origin = new BlockPos[1];
        BlockPos[] pit = new BlockPos[1];
        boolean[] fell = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++) {
                        for (int y = -3; y <= 1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    origin[0] = o.add(4, 2, 4);
                    for (int x = 0; x <= 8; x++) for (int y = 0; y <= 8; y++) {
                        if (x == 8 && y == 8) continue; // The block left to place.
                        world.setBlockState(origin[0].add(x, y, 0), Blocks.COBBLESTONE.getDefaultState());
                    }
                    // The pit, as the last fixture's: two by two and three deep, three blocks west of the wall's near end.
                    pit[0] = origin[0].add(-4, -3, 0);
                    for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) for (int y = 0; y <= 2; y++)
                        world.setBlockState(pit[0].add(dx, y, dz), Blocks.AIR.getDefaultState());
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 1));
                    inventory.setStack(2, new ItemStack(Items.DIRT, 64));
                    inventory.setStack(3, new ItemStack(Items.STONE_PICKAXE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = origin[0].add(8, 0, 2);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.startBuild(f.source, f.id, column, origin[0], 0) == 1, "Build started"))
                .createAndAdd(() -> {
                    // Staged, once: fallen into the pit as its scaffold comes down.
                    String status = f.runtime.buildStatus(f.id).orElse("");
                    if (!fell[0] && status.contains("Scaffolding down")) {
                        fell[0] = true;
                        f.player().refreshPositionAndAngles(pit[0].getX() + 0.5, pit[0].getY(), pit[0].getZ() + 0.5, 0, 0);
                        f.player().setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                        KindredMod.LOGGER.info("Build fixture: fallen into the pit as its scaffold came down: {}", status);
                    }
                    context.assertTrue(fell[0], "Its scaffold coming down: " + status);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertTrue(world.getBlockState(origin[0].add(8, 8, 0)).isOf(Blocks.COBBLESTONE), "The top placed");
                    // Its scaffold all down: nothing of dirt or cobblestone left standing off the wall about it.
                    int left = 0;
                    for (BlockPos at : BlockPos.iterate(origin[0].add(-3, 0, -3), origin[0].add(11, 9, 3))) {
                        if (at.getZ() == origin[0].getZ() && at.getX() >= origin[0].getX() && at.getX() <= origin[0].getX() + 8) continue;
                        var state = world.getBlockState(at);
                        if (state.isOf(Blocks.DIRT) || state.isOf(Blocks.COBBLESTONE)) left++;
                    }
                    context.assertTrue(left == 0, "Its scaffold taken down: " + left + " blocks left; the last word: " + f.runtime.nativeLastResult(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Up its own scaffold column, eight high, when the plan finds it blocked: down the column, digging out the block
     * underfoot, rather than walks to the ground a drop of more than three below that the navigator cannot take. In
     * #123's CI (run 42758107947, the life-first cabin; also #113, #117, #119) the builder, four up its own column, had its
     * plan come back "No way to reach minecraft:oak_stairs"; its walks over to the ground were "no walkable route" eight
     * times and the cabin stopped short of its roof. Staged: a wall nine high with two top blocks to place eight apart, two
     * cobblestone held; once the first is placed from its scaffold, its last cobblestone is taken from it, so the plan is
     * blocked ("Nothing in hand to place") while it stands high on its own column. It must be back on the ground within
     * a minute, and the build must not stop on its failed steps.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildBlockedOnScaffold", tickLimit = 6000)
    public void blockedUpItsOwnScaffoldItComesDownTheColumn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5500);
        var blocks = new java.util.ArrayList<dev.kindred.core.construction.Blueprint.Block>();
        for (int x = 0; x <= 8; x++) for (int y = 0; y <= 8; y++) blocks.add(new dev.kindred.core.construction.Blueprint.Block(
                new dev.kindred.core.construction.Pos(x, y, 0), dev.kindred.core.construction.BlockSpec.parse("minecraft:cobblestone")));
        var wall = new dev.kindred.core.construction.Blueprint("wall", "fixture", "Kindred", "fixture", blocks);
        BlockPos[] origin = new BlockPos[1];
        long[] takenAt = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++) {
                        for (int y = -3; y <= 1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    origin[0] = o.add(4, 2, 4);
                    for (int x = 0; x <= 8; x++) for (int y = 0; y <= 8; y++) {
                        if (y == 8 && (x == 0 || x == 8)) continue; // The two left to place, eight apart.
                        world.setBlockState(origin[0].add(x, y, 0), Blocks.COBBLESTONE.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 2));
                    inventory.setStack(2, new ItemStack(Items.DIRT, 64));
                    inventory.setStack(3, new ItemStack(Items.STONE_PICKAXE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = origin[0].add(8, 0, 2);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.startBuild(f.source, f.id, wall, origin[0], 0) == 1, "Build started"))
                .createAndAdd(() -> {
                    // Staged, once: the first top block placed, its last cobblestone taken from it while it stands high.
                    var world = context.getWorld();
                    var player = f.player();
                    boolean placed = world.getBlockState(origin[0].add(0, 8, 0)).isOf(Blocks.COBBLESTONE)
                            || world.getBlockState(origin[0].add(8, 8, 0)).isOf(Blocks.COBBLESTONE);
                    if (takenAt[0] < 0 && placed && player.isOnGround() && player.getBlockY() >= origin[0].getY() + 5) {
                        var inventory = player.getInventory();
                        for (int s = 0; s < inventory.size(); s++) if (inventory.getStack(s).isOf(Items.COBBLESTONE)) inventory.setStack(s, ItemStack.EMPTY);
                        takenAt[0] = world.getTime();
                        KindredMod.LOGGER.info("Build fixture: its last cobblestone taken at {} up its scaffold: {}", player.getBlockPos().toShortString(),
                                f.runtime.buildStatus(f.id).orElse(""));
                    }
                    context.assertTrue(takenAt[0] >= 0, "Up its scaffold with the first top placed: " + f.runtime.buildStatus(f.id).orElse("")
                            + " at " + player.getBlockPos().toShortString());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    String last = String.valueOf(f.runtime.nativeLastResult(f.id));
                    context.assertTrue(!last.contains("steps failed one after another"), "The build stopped: " + last);
                    context.assertTrue(player.isOnGround() && player.getBlockY() == origin[0].getY(), "Down on the ground: at "
                            + player.getBlockPos().toShortString() + " (ground " + origin[0].getY() + "); " + f.runtime.buildStatus(f.id).orElse("") + " / " + last);
                    context.assertTrue(context.getWorld().getTime() - takenAt[0] <= 1200, "Down within a minute");
                })
                .createAndAdd(() -> {
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Taking its scaffold down, the builder on a perch beside its own column, its top out of reach below: one block lower than
     * the wall's top, a drop of three onto the column's own top, it steps onto its column and takes it all down, rather than
     * wait "out of reach to take down" till its ticks run out (F-22c47). In a local class run on F-22c46's branch the house
     * cabin's builder slid off its column after its last slab onto the cabin, the column's top out of sight below, and waited
     * so; #113's base cabin (run 42756884925) ended the same way. Staged: the wall nine high with its top block placed from
     * its scaffold; once it is placed, the player is put on the edge of a stone set in the gap beside the column (the column
     * stands off the wall). Its scaffold must come all the way down.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildPerchBesideColumn", tickLimit = 8000)
    public void onAPerchBesideItsOwnColumnItStepsDownOntoItsTopAndTakesItDown(TestContext context) {
        perchBesideColumn(context, 1, true);
    }

    /**
     * As {@link #onAPerchBesideItsOwnColumnItStepsDownOntoItsTopAndTakesItDown}, the perch level with the wall's top, the
     * column's top a drop of four below, no way onto it and out of reach: the take-down is refused once and the build ends
     * there, built, its scaffolding left standing, within a minute, not waited on till its ticks run out (F-22c47).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildFarPerchBesideColumn", tickLimit = 8000)
    public void onAFarPerchBesideItsOwnColumnTheTakeDownIsRefusedOnceAndTheBuildEnds(TestContext context) {
        perchBesideColumn(context, 0, false);
    }

    /** The staging of the two perch fixtures: the perch {@code below} blocks under the wall's top level; {@code down}, whether it takes its scaffold all down. */
    private static void perchBesideColumn(TestContext context, int below, boolean down) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 7500);
        var blocks = new java.util.ArrayList<dev.kindred.core.construction.Blueprint.Block>();
        for (int x = 0; x <= 8; x++) for (int y = 0; y <= 8; y++) blocks.add(new dev.kindred.core.construction.Blueprint.Block(
                new dev.kindred.core.construction.Pos(x, y, 0), dev.kindred.core.construction.BlockSpec.parse("minecraft:cobblestone")));
        var wall = new dev.kindred.core.construction.Blueprint("wall", "fixture", "Kindred", "fixture", blocks);
        BlockPos[] origin = new BlockPos[1];
        boolean[] put = {false};
        int[] refused = {0};
        long[] putAt = {-1};
        String[] lastStatus = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++) {
                        for (int y = -3; y <= 1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    origin[0] = o.add(4, 2, 4);
                    for (int x = 0; x <= 8; x++) for (int y = 0; y <= 8; y++) {
                        if (x == 8 && y == 8) continue; // The block left to place.
                        world.setBlockState(origin[0].add(x, y, 0), Blocks.COBBLESTONE.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 1));
                    inventory.setStack(2, new ItemStack(Items.DIRT, 64));
                    inventory.setStack(3, new ItemStack(Items.STONE_PICKAXE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = origin[0].add(8, 0, 2);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    context.runAtEveryTick(() -> {
                        String last = String.valueOf(f.runtime.nativeLastResult(f.id));
                        if (put[0] && last.contains("Nothing solid to break")) refused[0]++;
                        f.runtime.buildStatus(f.id).ifPresent(status -> lastStatus[0] = status);
                    });
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.startBuild(f.source, f.id, wall, origin[0], 0) == 1, "Build started"))
                .createAndAdd(() -> {
                    // Staged, once: its column (dirt beside the wall's near end, off either face) down to two or more below
                    // the wall's top, the player put on the wall's top at the edge over it.
                    var world = context.getWorld();
                    if (!put[0] && context.getWorld().getBlockState(origin[0].add(8, 8, 0)).isOf(Blocks.COBBLESTONE)) {
                        // Its column: dirt off the wall beside its top block, with a top block of the wall beside it to stand on.
                        for (int dx = -2; dx <= 2 && !put[0]; dx++) for (int dz = -2; dz <= 2 && !put[0]; dz++) {
                            BlockPos column = origin[0].add(8 + dx, 0, dz);
                            if (dz == 0 && 8 + dx <= 8) continue; // The wall itself.
                            int top = -1;
                            for (int y = 0; y <= 9; y++) if (world.getBlockState(column.up(y)).isOf(Blocks.DIRT)) top = y;
                            if (top < 1 || top > 6 || !world.getBlockState(column.up(7)).isAir() || !world.getBlockState(column.up(8)).isAir()) continue;
                            int level = 8 - below;
                            for (var side : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                                BlockPos beside = column.up(level).offset(side);
                                // Toward the wall: the ledge it stands on, the wall's own top or, the column two off, a stone
                                // put in the gap (staged), level with the wall's top.
                                BlockPos wallward = origin[0].add(8, 8, 0);
                                if (beside.getManhattanDistance(wallward) >= column.up(level).getManhattanDistance(wallward)) continue;
                                if (!world.getBlockState(beside).isOf(Blocks.COBBLESTONE)) {
                                    if (!world.getBlockState(beside).isAir()) continue;
                                    world.setBlockState(beside, Blocks.STONE.getDefaultState());
                                }
                                if (!world.getBlockState(beside.up()).isAir()) continue;
                                double x = column.getX() + 0.5 + side.getOffsetX() * 0.45, z = column.getZ() + 0.5 + side.getOffsetZ() * 0.45;
                                f.player().refreshPositionAndAngles(x, origin[0].getY() + level + 1, z, 0, 0);
                                putAt[0] = world.getTime();
                                f.player().setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                                put[0] = true;
                                KindredMod.LOGGER.info("Build fixture: put on the wall's edge over its column at {}, its top {} up: {}", column.toShortString(), top,
                                        f.runtime.buildStatus(f.id).orElse(""));
                                break;
                            }
                        }
                    }
                    context.assertTrue(put[0], "Its column coming down: " + f.runtime.buildStatus(f.id).orElse(""));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    String last = String.valueOf(f.runtime.nativeLastResult(f.id));
                    context.assertTrue(!last.contains("steps failed one after another"), "The build stopped: " + last);
                    int left = 0;
                    for (BlockPos at : BlockPos.iterate(origin[0].add(-3, 0, -3), origin[0].add(11, 9, 3))) {
                        if (world.getBlockState(at).isOf(Blocks.DIRT)) left++;
                    }
                    if (down) {
                        context.assertTrue(left == 0, "Its scaffold taken down: " + left + " left; last " + lastStatus[0] + "; " + last);
                    } else {
                        // Refused once and ended, within a minute of being put there: not waited on till its ticks ran out.
                        context.assertTrue(world.getTime() - putAt[0] <= 1200, "Ended within a minute: " + (world.getTime() - putAt[0]) + " ticks");
                        context.assertTrue(String.valueOf(f.runtime.said(f.id)).contains("left standing"), "Its last word: " + lastStatus[0] + "; said " + f.runtime.said(f.id));
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Its wall's top block placed from its own scaffold, the player stood 0.4 of a block off its column's middle
     * toward a bottom slab in the next cell, level with the block underfoot (staged; the cabin's roof on #136's CI, run
     * 42826864347). Dug out from there, the block gave way under a body overlapping the slab's cell, and the slab caught it
     * half a block down: it slid onto the roof, where no walk could start, and the cabin stopped short. It steps over the
     * column's middle first and goes down its own scaffold, never onto the slab, and takes the scaffold down (F-22c57).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildDownBesideSlab", tickLimit = 6000)
    public void aScaffoldBesideASlabIsClimbedDownOverItsMiddleNotOntoTheSlab(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5500);
        var blocks = new java.util.ArrayList<dev.kindred.core.construction.Blueprint.Block>();
        for (int x = 0; x <= 4; x++) for (int y = 0; y <= 7; y++) blocks.add(new dev.kindred.core.construction.Blueprint.Block(
                new dev.kindred.core.construction.Pos(x, y, 0), dev.kindred.core.construction.BlockSpec.parse("minecraft:cobblestone")));
        var wall = new dev.kindred.core.construction.Blueprint("wall", "fixture", "Kindred", "fixture", blocks);
        BlockPos[] origin = new BlockPos[1];
        BlockPos[] slab = new BlockPos[1];
        boolean[] slid = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -6; x <= 12; x++) for (int z = -6; z <= 12; z++) {
                        for (int y = -1; y <= 1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    origin[0] = o.add(3, 2, 3);
                    for (int x = 0; x <= 4; x++) for (int y = 0; y <= 7; y++) {
                        if (x == 4 && y == 7) continue; // The block left to place, from its scaffold.
                        world.setBlockState(origin[0].add(x, y, 0), Blocks.COBBLESTONE.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 1));
                    inventory.setStack(2, new ItemStack(Items.DIRT, 64));
                    inventory.setStack(3, new ItemStack(Items.STONE_PICKAXE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = origin[0].add(4, 0, 2);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.startBuild(f.source, f.id, wall, origin[0], 0) == 1, "Build started"))
                .createAndAdd(() -> {
                    // Staged, once, as the top goes in: the slab beside its column, the player 0.4 off toward it.
                    var world = context.getWorld();
                    var player = f.player();
                    if (slab[0] == null && world.getBlockState(origin[0].add(4, 7, 0)).isOf(Blocks.COBBLESTONE)) {
                        BlockPos feet = player.getBlockPos();
                        context.assertTrue(player.isOnGround() && feet.getY() > origin[0].getY() + 1 && world.getBlockState(feet.down()).isOf(Blocks.DIRT),
                                "On its scaffold as the top went in: " + feet + " on " + world.getBlockState(feet.down()));
                        for (var d : List.of(net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST,
                                net.minecraft.util.math.Direction.SOUTH, net.minecraft.util.math.Direction.NORTH)) {
                            BlockPos beside = feet.offset(d);
                            if (beside.getZ() == origin[0].getZ() || !world.getBlockState(beside.down()).isAir() || !world.getBlockState(beside).isAir()
                                    || !world.getBlockState(beside.up()).isAir()) continue;
                            slab[0] = beside.down();
                            world.setBlockState(slab[0], Blocks.STONE_SLAB.getDefaultState());
                            player.refreshPositionAndAngles(feet.getX() + 0.5 + 0.4 * d.getOffsetX(), player.getY(),
                                    feet.getZ() + 0.5 + 0.4 * d.getOffsetZ(), player.getYaw(), player.getPitch());
                            player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                            KindredMod.LOGGER.info("Build fixture: a slab beside its scaffold at {}, the player at {} {} {}", slab[0],
                                    player.getX(), player.getY(), player.getZ());
                            break;
                        }
                        context.assertTrue(slab[0] != null, "A cell beside its scaffold for the slab, at " + feet);
                    }
                    context.assertTrue(slab[0] != null, "The top placed: " + f.runtime.buildStatus(f.id).orElse(""));
                })
                .createAndAdd(() -> {
                    // Watched each tick from here: never stood on the slab's top, half a block down its column. Its block
                    // there is still the column's, dug out; the slab under the overlapping box holds it up.
                    var player = f.player();
                    if (!slid[0] && player.isOnGround() && Math.abs(player.getY() - (slab[0].getY() + 0.5)) < 1.0e-3) {
                        slid[0] = true;
                        KindredMod.LOGGER.info("Build fixture: slid onto the slab at {}: {} {} {}", slab[0], player.getX(), player.getY(), player.getZ());
                    }
                    context.assertFalse(slid[0], "Slid onto the slab at " + slab[0] + "; the last word: " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                            "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int left = 0;
                    for (BlockPos at : BlockPos.iterate(origin[0].add(-3, 0, -3), origin[0].add(7, 9, 3))) {
                        if (at.getZ() == origin[0].getZ() && at.getX() >= origin[0].getX() && at.getX() <= origin[0].getX() + 4) continue;
                        var state = world.getBlockState(at);
                        if (state.isOf(Blocks.DIRT) || state.isOf(Blocks.COBBLESTONE)) left++;
                    }
                    context.assertTrue(left == 0, "Its scaffold taken down: " + left + " blocks left; the last word: " + f.runtime.nativeLastResult(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A bed to build, two of its three wool held, and the third lying beside the player, not to be picked up for four
     * seconds (staged: a player's toss is two): it waits by it, picks it up once it may, makes the bed and puts it in.
     * Before, each look at the drop while it waited counted as a try, the plan being asked again every few ticks, and
     * after a second it let the drop lie and went out to look for sheep (on CI on #12 a trading hall was left short of
     * its beds' wool after all nine sheep were shorn).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildWoolDrop", tickLimit = 3000)
    public void aDropOfWoolItWaitsBesideIsPickedUpAndMadeIntoTheBed(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2500);
        var blocks = new java.util.ArrayList<dev.kindred.core.construction.Blueprint.Block>();
        blocks.add(new dev.kindred.core.construction.Blueprint.Block(new dev.kindred.core.construction.Pos(0, 0, 0),
                dev.kindred.core.construction.BlockSpec.parse("minecraft:white_bed[facing=north,part=head]")));
        blocks.add(new dev.kindred.core.construction.Blueprint.Block(new dev.kindred.core.construction.Pos(0, 0, 1),
                dev.kindred.core.construction.BlockSpec.parse("minecraft:white_bed[facing=north,part=foot]")));
        var bed = new dev.kindred.core.construction.Blueprint("bed", "fixture", "Kindred", "fixture", blocks);
        BlockPos[] origin = new BlockPos[1];
        net.minecraft.entity.ItemEntity[] drop = new net.minecraft.entity.ItemEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -8; x <= 24; x++) for (int z = -8; z <= 24; z++) {
                        for (int y = -3; y <= 1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    origin[0] = o.add(4, 2, 4);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(1, new ItemStack(Items.WHITE_WOOL, 2));
                    inventory.setStack(2, new ItemStack(Items.OAK_PLANKS, 3));
                    inventory.setStack(3, new ItemStack(Items.CRAFTING_TABLE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // Where it stands and the wool lies: the middle of one chunk, whichever way the test's ground lies across
                    // chunks. On CI the wool once lay a block over the edge of the player's chunk, in one that did not tick,
                    // and never came to rest.
                    BlockPos stand = o.add(12, 2, 12);
                    stand = stand.add(8 - (stand.getX() & 15), 0, 8 - (stand.getZ() & 15));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    // Staged: the third wool, lying beside it, and not to be picked up for four seconds.
                    var wool = new net.minecraft.entity.ItemEntity(world, stand.getX() + 1.3, stand.getY(), stand.getZ() + 0.5, new ItemStack(Items.WHITE_WOOL, 1), 0, 0, 0);
                    wool.setPickupDelay(80);
                    world.spawnEntity(wool);
                    drop[0] = wool;
                })
                .createAndAdd(() -> context.assertTrue(drop[0].isOnGround(), "The wool lying: " + drop[0].getPos() + ", alive " + drop[0].isAlive()))
                .createAndAdd(() -> context.assertTrue(f.runtime.startBuild(f.source, f.id, bed, origin[0], 0) == 1, "Build started"))
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    KindredMod.LOGGER.info("Wool drop fixture: the drop {}, {} wool held, the bed {}", drop[0].isAlive() ? "lying" : "picked up",
                            player.getInventory().count(Items.WHITE_WOOL), world.getBlockState(origin[0]));
                    context.assertTrue(!drop[0].isAlive(), "The wool picked up, not let lie: " + drop[0].getPos());
                    context.assertTrue(world.getBlockState(origin[0]).isOf(Blocks.WHITE_BED) && world.getBlockState(origin[0].add(0, 0, 1)).isOf(Blocks.WHITE_BED),
                            "The bed made and put in: " + world.getBlockState(origin[0]) + " / " + f.runtime.nativeLastResult(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
