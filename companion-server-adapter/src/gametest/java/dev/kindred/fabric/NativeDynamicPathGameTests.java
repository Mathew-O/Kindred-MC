package dev.kindred.fabric;

import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * In plain terms: a GameTest: in a real test world, a player running down a long hallway is checked to get
 * round blocks dropped in its way ahead of it (a pillar in the middle, or two of the three lanes blocked)
 * without ever stopping, slowing much or turning sharply. Two more tests walk it across open ground past a
 * penned wither skeleton: told to avoid mobs it stays far enough away never to be noticed, and walking
 * straight past, as a control, it is noticed.
 *
 * Technical: Routes that change under the player's feet (F-10g): a fortress hallway, three wide and four
 * high, walked end to end at a run, with blocks put down in its way once the player is under way. A player
 * sees the block coming and steps round it without stopping; so must the native player, sampled every tick.
 */
public final class NativeDynamicPathGameTests implements FabricGameTest {
    /** Ticks a run of the empty hallway takes, for comparison: sprinting 30 blocks is about 110 ticks. */
    private static final int HALL = 30;

    /** A hallway of nether bricks along +X from {@code o}: floor at y-1, walls at z = ±2, ceiling at y = 4. */
    private static void hallway(TestContext context, BlockPos o) {
        var world = context.getWorld();
        var bricks = Blocks.NETHER_BRICKS.getDefaultState();
        for (int x = -2; x <= HALL + 2; x++) for (int z = -4; z <= 4; z++) for (int y = -1; y <= 5; y++) {
            boolean shell = Math.abs(z) <= 2 && (y == -1 || y == 4 || Math.abs(z) == 2);
            world.setBlockState(o.add(x, y, z), shell ? bricks : Blocks.AIR.getDefaultState());
        }
        // The far end closed, as a hallway's turn would be.
        for (int z = -1; z <= 1; z++) for (int y = 0; y <= 3; y++) world.setBlockState(o.add(HALL + 2, y, z), bricks);
    }

    /**
     * Runs the hallway with {@code lanes} of its three blocked by a three-high pillar half way along, put down when the
     * player is eight blocks from it. Asserts it never comes to a stop, never snaps round, and gets there.
     */
    private static void runWithPillar(TestContext context, String name, int[] lanes) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos[] origin = new BlockPos[1];
        int[] ticks = {0}, placedAt = {-1}, stops = {0}, logged = {0};
        double[] minSpeed = {Double.MAX_VALUE};
        float[] maxTurn = {0}, lastYaw = {0};
        Vec3d[] last = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    BlockPos o = origin[0] = f.player().getBlockPos().up(20).toImmutable();
                    hallway(context, o);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "In the hallway"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    lastYaw[0] = f.player().getYaw();
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, origin[0].add(HALL, 0, 0), 0.8) == 1, "Route requested");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    ticks[0]++;
                    double x = player.getX() - origin[0].getX();
                    int pillar = HALL / 2;
                    if (placedAt[0] < 0 && x >= pillar - 8) {
                        for (int lane : lanes) for (int y = 0; y <= 2; y++) {
                            context.getWorld().setBlockState(origin[0].add(pillar, y, lane), Blocks.NETHER_BRICKS.getDefaultState());
                        }
                        placedAt[0] = ticks[0];
                    }
                    if (placedAt[0] >= 0) {
                        float delta = MathHelper.wrapDegrees(player.getYaw() - lastYaw[0]);
                        maxTurn[0] = Math.max(maxTurn[0], Math.abs(delta));
                        // Passing the pillar: from the moment it is put down to two blocks past it.
                        if (x < pillar + 2) {
                            // Ground covered this tick: on the ground vanilla reports velocity after friction, about
                            // half of it, so the position is what shows a stop.
                            double speed = last[0] == null ? 0.3 : Math.hypot(player.getX() - last[0].x, player.getZ() - last[0].z);
                            minSpeed[0] = Math.min(minSpeed[0], speed);
                            if (speed < 0.08) stops[0]++;
                        }
                    }
                    lastYaw[0] = player.getYaw();
                    last[0] = player.getPos();
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_goto"),
                            "Arrives: " + activity + " at " + player.getPos());
                })
                .createAndAdd(() -> {
                    String summary = String.format(Locale.ROOT, "%d ticks (%d after the pillar), slowest %.3f blocks a tick past it, %d stopped ticks, max turn %.1f deg",
                            ticks[0], ticks[0] - placedAt[0], minSpeed[0], stops[0], maxTurn[0]);
                    if (logged[0]++ == 0) KindredMod.LOGGER.info("{} hallway: {}", name, summary);
                    context.assertTrue(placedAt[0] >= 0, "The pillar went down mid-run");
                    context.assertTrue(f.player().getBlockPos().getSquaredDistance(origin[0].add(HALL, 0, 0)) <= 4, "At the far end: " + f.player().getBlockPos());
                    context.assertTrue(stops[0] == 0, "Never came to a stop going round: " + summary);
                    context.assertTrue(minSpeed[0] > 0.15, "Kept its pace up past the pillar: " + summary);
                    context.assertTrue(maxTurn[0] <= 30f, "Turned as a player turns: " + summary);
                    context.assertTrue(ticks[0] <= 170, "No longer than a short detour takes: " + summary);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * An open floor with a wither skeleton penned six blocks beside the straight way across, well inside the sixteen
     * blocks at which it notices a player it can see. Walking with {@code avoid}, the player is to keep out of its
     * notice the whole way; walking without, it is to be noticed (the control, which shows the skeleton is awake).
     */
    private static void pastWitherSkeleton(TestContext context, String name, boolean avoid) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos[] origin = new BlockPos[1];
        net.minecraft.entity.mob.WitherSkeletonEntity[] skeleton = new net.minecraft.entity.mob.WitherSkeletonEntity[1];
        boolean[] noticed = {false};
        double[] closest = {Double.MAX_VALUE};
        int[] logged = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = origin[0] = f.player().getBlockPos().up(20).toImmutable();
                    forceChunks(world, o, true);
                    for (int x = -4; x <= 36; x++) for (int z = -26; z <= 12; z++) {
                        world.setBlockState(o.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 4; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = 14; x <= 16; x++) for (int z = 5; z <= 7; z++) {
                        if (x != 15 || z != 6) world.setBlockState(o.add(x, 0, z), Blocks.NETHER_BRICK_FENCE.getDefaultState());
                    }
                    var mob = net.minecraft.entity.EntityType.WITHER_SKELETON.create(world);
                    mob.refreshPositionAndAngles(o.getX() + 15.5, o.getY(), o.getZ() + 6.5, 180, 0);
                    mob.setPersistent();
                    world.spawnEntity(mob);
                    skeleton[0] = mob;
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the floor"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    BlockPos goal = origin[0].add(HALL, 0, 0);
                    int requested = avoid ? f.runtime.nativeGotoAvoiding(f.source, f.id, goal, 0.8, "") : f.runtime.nativeGoto(f.source, f.id, goal, 0.8);
                    context.assertTrue(requested == 1, "Route requested");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    if (skeleton[0].getTarget() == player) noticed[0] = true;
                    closest[0] = Math.min(closest[0], player.distanceTo(skeleton[0]));
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(noticed[0] && !avoid || activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_goto"),
                            "Arrives: " + activity + " at " + player.getPos());
                })
                .createAndAdd(() -> {
                    String summary = String.format(Locale.ROOT, "closest %.1f blocks, noticed %s", closest[0], noticed[0]);
                    if (logged[0]++ == 0) KindredMod.LOGGER.info("{} past a wither skeleton: {}", name, summary);
                    if (avoid) {
                        context.assertTrue(!noticed[0], "Kept out of its notice: " + summary);
                        context.assertTrue(closest[0] > 15.5, "Never within its sixteen blocks: " + summary);
                        context.assertTrue(f.player().getBlockPos().getSquaredDistance(origin[0].add(HALL, 0, 0)) <= 4, "Across: " + f.player().getBlockPos());
                    } else {
                        context.assertTrue(noticed[0], "Walking straight past, it is noticed: " + summary);
                    }
                    skeleton[0].discard();
                    forceChunks(context.getWorld(), origin[0], false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** The test server ticks no mob outside the test's own chunks (it sets no simulation distance); a live one does. */
    private static void forceChunks(net.minecraft.server.world.ServerWorld world, BlockPos o, boolean forced) {
        for (int x = o.getX() - 8; x <= o.getX() + 44; x += 16) for (int z = o.getZ() - 32; z <= o.getZ() + 16; z += 16) {
            world.setChunkForced(x >> 4, z >> 4, forced);
        }
    }

    /** A wither skeleton six blocks off the way: round it beyond the sixteen blocks at which it would notice. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skeletonBerth", tickLimit = 900)
    public void aWitherSkeletonBesideTheWayIsGivenAWideBerth(TestContext context) {
        pastWitherSkeleton(context, "Sidestep", true);
    }

    /** The control: the same walk minding no mobs, and the skeleton notices the player. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skeletonNotice", tickLimit = 900)
    public void walkingStraightPastAWitherSkeletonIsNoticed(TestContext context) {
        pastWitherSkeleton(context, "Straight", false);
    }

    /** A three-high pillar dropped in the middle of the hallway eight blocks ahead: round it at a run. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hallPillar", tickLimit = 600)
    public void aPillarDroppedInTheHallwayIsRunRoundWithoutStopping(TestContext context) {
        runWithPillar(context, "Runner", new int[] {0});
    }

    /** Two of the hallway's three lanes walled eight blocks ahead: through the one left, still at a run. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hallGap", tickLimit = 600)
    public void twoThirdsOfTheHallwayBlockedAheadIsSlippedThroughWithoutStopping(TestContext context) {
        runWithPillar(context, "Slipper", new int[] {0, -1});
    }
}
