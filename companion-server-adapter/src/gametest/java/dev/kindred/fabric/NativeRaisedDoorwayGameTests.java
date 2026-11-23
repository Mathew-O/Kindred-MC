package dev.kindred.fabric;

import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a GameTest, staged: a wooden door stands on a sill one block up, in a wall three high, with a floor
 * one block up beyond it. A player on the ground walks to a spot on that floor, and the only way is up through the door.
 * It must get there, not stop in the doorway.
 *
 * Technical: E-71b1a. In #359's class runs (the compound's hall, working from where it stands, E-71b1) a walk across the
 * hall went up a step into the hall's door, which stood on its plank floor one up, and the body stopped in the door's own
 * cell; every walk after set out from there and was "Route ... is blocked" until the hall stopped "8 steps failed one after
 * another" (trace: the body still and on the ground at each placement, the door placed three blocks off, the first walk
 * through it the one that stuck). Staged: the door is set, closed, by the test; the walk is the navigator's own.
 */
public final class NativeRaisedDoorwayGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "raisedDoorway", tickLimit = 1200)
    public void aWalkUpThroughADoorOnASillOneUpGetsThroughNotStuckInTheDoorway(TestContext context) {
        scene(context, Direction.SOUTH, true);
    }

    /** Up onto the sill and down again: the ground the same height on both sides, as the hall's walk across it was. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "raisedDoorwayOver", tickLimit = 1200)
    public void aWalkUpOverADoorOnASillAndDownAgainGetsThrough(TestContext context) {
        scene(context, Direction.SOUTH, false);
    }

    /** The door facing the walker, its leaf on the near side, up onto the floor beyond. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "raisedDoorwayFacing", tickLimit = 1200)
    public void aWalkUpThroughADoorFacingItOnASillGetsThrough(TestContext context) {
        scene(context, Direction.NORTH, true);
    }

    /** The door facing the walker, up over the sill and down again. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "raisedDoorwayFacingOver", tickLimit = 1200)
    public void aWalkUpOverADoorFacingItAndDownAgainGetsThrough(TestContext context) {
        scene(context, Direction.NORTH, false);
    }

    private static void scene(TestContext context, Direction facing, boolean raisedBeyond) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos[] goal = new BlockPos[1], door = new BlockPos[1];
        String[] seen = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Doorwalker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    FixtureGround.keep(context, o.add(-1, 1, -1), o.add(9, 7, 12));
                    for (int x = -1; x <= 9; x++) for (int z = -1; z <= 12; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 7; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                        // Beyond the wall, a floor one block up, as the hall's plank floor stood over its ground.
                        if (raisedBeyond && z >= 6) world.setBlockState(o.add(x, 2, z), Blocks.OAK_PLANKS.getDefaultState());
                    }
                    // The wall, three high; in it, at x 4, a plank sill and a closed door standing on it.
                    for (int x = -1; x <= 9; x++) for (int y = 2; y <= 4; y++) {
                        if (x != 4) world.setBlockState(o.add(x, y, 5), Blocks.COBBLESTONE.getDefaultState());
                    }
                    door[0] = o.add(4, 3, 5);
                    world.setBlockState(o.add(4, 2, 5), Blocks.OAK_PLANKS.getDefaultState());
                    BlockState lower = Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, facing)
                            .with(DoorBlock.HINGE, DoorHinge.LEFT).with(DoorBlock.OPEN, false).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
                    world.setBlockState(door[0], lower, 3);
                    world.setBlockState(door[0].up(), lower.with(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
                    goal[0] = o.add(4, raisedBeyond ? 3 : 2, 9);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 4.5, o.getY() + 2, o.getZ() + 1.5, 0, 0);
                    player.setVelocity(0, 0, 0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, goal[0], 0.5) == 1, "Route requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE"), "The walk over: " + activity + " at " + f.player().getPos());
                    Vec3d at = f.player().getPos();
                    BlockPos cell = BlockPos.ofFloored(at.x, at.y + 0.5, at.z);
                    seen[0] = activity.work() + "; the body at " + String.format("%.2f, %.2f, %.2f", at.x, at.y, at.z)
                            + (cell.equals(door[0]) ? ", in the doorway" : "");
                    KindredMod.LOGGER.info("Raised doorway ({}, {}): {}", facing, raisedBeyond ? "onto the floor" : "over and down", seen[0]);
                    context.assertTrue(activity.work().startsWith("Completed native_goto")
                                    && Math.hypot(at.x - (goal[0].getX() + 0.5), at.z - (goal[0].getZ() + 0.5)) < 0.8 && Math.abs(at.y - goal[0].getY()) < 0.1,
                            "Through the door onto the floor beyond: " + seen[0]);
                })
                .createAndAdd(f.runtime::stop)
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The trace's own cells (E-71b1a): the compound's hall as it stood when the walk out of its door was refused "is blocked",
     * one course of wall up on its plank floor, a block over the ground outside, the door in its south wall standing open.
     * The body where the trace had it, in the doorway on the floor, walks to the spot outside it was sent to.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tracedDoorway", tickLimit = 1200)
    public void fromTheHallsOpenDoorwayItWalksOutToTheSpotItWasSentTo(TestContext context) {
        traced(context, new Vec3d(0.4875, -1, 0.4998), false);
    }

    /** The same hall, the walk set out from inside it, so it comes through the open door on its own way out. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tracedDoorwayInside", tickLimit = 1200)
    public void fromInsideTheHallItWalksOutThroughTheOpenDoorToTheSpotItWasSentTo(TestContext context) {
        traced(context, new Vec3d(0.5, -1, -4.5), false);
    }

    /**
     * The trace's hall seen in a mirror, east for west: the door hung on its other side (hinge right), its open leaf along
     * the doorway's east edge, the body flush against it and the spot outside to the south-east.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tracedDoorwayMirrored", tickLimit = 1200)
    public void fromTheMirroredHallsOpenDoorwayItWalksOutToTheSpotItWasSentTo(TestContext context) {
        traced(context, new Vec3d(0.5125, -1, 0.4998), true);
    }

    private static void traced(TestContext context, Vec3d from, boolean mirrored) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos[] goal = new BlockPos[1], door = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Doorwalker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // The trace's body cell; the dump ran from two below it to three above, the ground outside one lower still.
                    BlockPos c = context.getAbsolutePos(new BlockPos(10, 4, 20));
                    FixtureGround.keep(context, c.add(-11, -3, -21), c.add(11, 5, 21));
                    for (int dx = -11; dx <= 11; dx++) for (int dz = -21; dz <= 21; dz++) {
                        world.setBlockState(c.add(dx, -3, dz), Blocks.STONE.getDefaultState(), 2);
                        for (int dy = -2; dy <= 5; dy++) world.setBlockState(c.add(dx, dy, dz), Blocks.AIR.getDefaultState(), 2);
                    }
                    for (int dy = -2; dy <= 3; dy++) for (int dz = -20; dz <= 20; dz++) for (int dx = -10; dx <= 10; dx++) {
                        char k = LAYERS[dy + 2][dz + 20].charAt(dx + 10);
                        if (k == '.') continue;
                        String spec = mirrored ? mirror(LEGEND.get(k)) : LEGEND.get(k);
                        try {
                            world.setBlockState(c.add(mirrored ? -dx : dx, dy, dz), BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), spec, false).blockState(), 2);
                        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                            throw new IllegalStateException(spec, e);
                        }
                    }
                    door[0] = c.down();
                    context.assertTrue(world.getBlockState(door[0]).isOf(Blocks.OAK_DOOR) && world.getBlockState(door[0]).get(DoorBlock.OPEN), "The hall's door, open");
                    goal[0] = c.add(mirrored ? 4 : -4, -2, 6);
                    var player = f.player();
                    player.refreshPositionAndAngles(c.getX() + from.x, c.getY() + from.y, c.getZ() + from.z, 90, 0);
                    player.setVelocity(0, 0, 0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, goal[0], 0.5) == 1, "Route requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE"), "The walk over: " + activity + " at " + f.player().getPos());
                    Vec3d at = f.player().getPos();
                    String seen = activity.work() + "; the body at " + String.format("%.3f, %.3f, %.3f", at.x, at.y, at.z)
                            + (BlockPos.ofFloored(at.x, at.y + 0.5, at.z).equals(door[0]) || BlockPos.ofFloored(at).equals(door[0].up()) ? ", in the doorway" : "");
                    KindredMod.LOGGER.info("Traced doorway (from {}{}): {}", from, mirrored ? ", mirrored" : "", seen);
                    context.assertTrue(activity.work().startsWith("Completed native_goto")
                                    && Math.hypot(at.x - (goal[0].getX() + 0.5), at.z - (goal[0].getZ() + 0.5)) < 0.8 && Math.abs(at.y - goal[0].getY()) < 0.1,
                            "Out of the hall to the spot outside: " + seen);
                })
                .createAndAdd(f.runtime::stop)
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** The trace's legend: a character for each block state in the dump. */
    /** A block state as a mirror east for west shows it: east and west swapped, a door's hinge on its other side. */
    private static String mirror(String spec) {
        return spec.replace("facing=east", "facing=@").replace("facing=west", "facing=east").replace("facing=@", "facing=west")
                .replace("hinge=left", "hinge=@").replace("hinge=right", "hinge=left").replace("hinge=@", "hinge=right");
    }

    private static final java.util.Map<Character, String> LEGEND = java.util.Map.ofEntries(
            java.util.Map.entry('#', "minecraft:cobblestone"),
            java.util.Map.entry('L', "minecraft:oak_log[axis=y]"),
            java.util.Map.entry('=', "minecraft:oak_planks"),
            java.util.Map.entry('D', "minecraft:chest[facing=east,type=single,waterlogged=false]"),
            java.util.Map.entry('d', "minecraft:crafting_table"),
            java.util.Map.entry('t', "minecraft:furnace[facing=south,lit=false]"),
            java.util.Map.entry('T', "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
            java.util.Map.entry('c', "minecraft:oak_door[facing=north,half=lower,hinge=left,open=true,powered=false]"),
            java.util.Map.entry('f', "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"),
            java.util.Map.entry('a', "minecraft:oak_door[facing=north,half=upper,hinge=left,open=true,powered=false]"),
            java.util.Map.entry('b', "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"),
            java.util.Map.entry('e', "minecraft:wall_torch[facing=east]"),
            java.util.Map.entry('g', "minecraft:wall_torch[facing=north]"),
            java.util.Map.entry('h', "minecraft:wall_torch[facing=south]"),
            java.util.Map.entry('i', "minecraft:wall_torch[facing=west]"));

    /** The trace's cells, a layer at a time from two below the body's cell to three above; x -10 to 10 across, z -20 to 20 down; '.' is air. */
    private static final String[][] LAYERS = {
            {"..#=....=======..=#=.", "..#=....=======..=#=.", "..#=.............=#=.", "..#=.............=#=.", "..#=.............=#=.", "..#===============#==", "..###################", "..#===============#==", "..#=.............=#=.", "..#=.............=#=.", "..#=..=========..=#=.", "..#=..=========..=#=.", "..#=..=========..=#=.", "..#=..=========..=#=.", "..#=..=========..=#=.", "..#=..=========..=#=.", "..#=..=========..=#=.", "..#=..=========..=#=.", "..#=..=========..=#=.", "..#=..=========..=#==", "..#=..=========..=###", "..#=.............=#..", "..#=.............=#..", "..#=.............=#..", "..#===============#..", "..#################..", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", "....................."},
            {"..#=....#.....#..=#=.", "..#=....L##T##L..=#=.", "..#=e...........i=#=e", "..#=.............=#=.", "..#=.g...g...g...=#=.", "..#===============#==", "..###################", "..#===============#==", "..#=.h...h...h...=#=.", "..#=e...........i=#=e", "..#=..L#######L..=#=.", "..#=..#D..ttd.#..=#=.", "..#=..#.......#..=#=.", "..#=e.#D......#.i=#=e", "..#=..#.......#..=#=.", "..#=..#D......#..=#=.", "..#=..#.......#..=#=.", "..#=e.#.......#.i=#=e", "..#=..#.......#..=#=.", "..#=..#.......#..=#==", "..#=..L###c###L..=###", "..#=e...........i=#..", "..#=.............=#..", "..#=.g...g...g...=#..", "...===============#..", ".......############..", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", "....................."},
            {"..#=....#.....#..=#=.", "..#=....L##f##L..=#=.", "..#=.............=#=.", "..#=.............=#=.", "..#=.............=#=.", "..#===============#==", "..###################", "..................#==", "..................#=.", "..................#=.", "..................#=.", "..................#=.", "..................#=.", "..................#=.", "..................#=.", "..................#=.", "..................#=.", "..................#=.", "..................#=.", "..................#==", "..........a.......###", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", "....................."},
            {"........#.....#......", "........L#####L......", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", "....................."},
            {"........=======......", "........bbbbbbb......", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", "....................."},
            {"........bbbbbbb......", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", ".....................", "....................."},
    };
}
