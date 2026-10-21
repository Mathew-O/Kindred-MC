package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Pathfinding travel and recorded roaming breaks with real native physics, timing and pickup. */
public final class NativeNavigationGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void gotoRoutesAroundAWallAndStepsUpOntoTheGoal(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos goal = context.getAbsolutePos(new BlockPos(6, 3, 1));
        Vec3d[] start = new Vec3d[1];
        int[] startAge = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Pathfinder"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing on the fixture floor"))
                .createAndAdd(() -> {
                    for (int z = 0; z <= 5; z++) for (int y = 2; y <= 3; y++) context.setBlockState(5, y, z, Blocks.STONE);
                    context.setBlockState(6, 2, 1, Blocks.STONE);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    start[0] = f.player().getPos();
                    startAge[0] = f.player().age;
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, goal, 0.5) == 1, "Route requested");
                    context.assertTrue(f.player().getPos().equals(start[0]), "A request never teleports the body");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_goto"),
                            "Goto completes: " + activity + " at " + f.player().getPos());
                })
                .createAndAdd(() -> {
                    Vec3d at = f.player().getPos();
                    context.assertTrue(Math.hypot(at.x - (goal.getX() + 0.5), at.z - (goal.getZ() + 0.5)) < 0.8 && Math.abs(at.y - goal.getY()) < 0.1,
                            "Arrived on top of the step at " + at);
                    context.assertTrue(f.player().age - startAge[0] >= 20, "Travel took real ticks of native movement");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void breakUsesTheOwnedPickaxeAndPicksUpItsOwnDrop(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos stone = context.getAbsolutePos(new BlockPos(5, 2, 3));
        BlockPos guarded = context.getAbsolutePos(new BlockPos(3, 2, 6));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Breaker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(stone, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(guarded, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(guarded.up(), Blocks.LAVA.getDefaultState());
                    f.player().getInventory().setStack(15, new ItemStack(Items.WOODEN_PICKAXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeBreak(f.source, f.id, guarded) == 0, "A break that would release lava is refused");
                    context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("UNLOADED"),
                            "The refusal reserved no activity");
                    context.assertTrue(f.runtime.nativeBreak(f.source, f.id, stone) == 1, "Stone break requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_break"),
                            "Break completes: " + activity);
                })
                .createAndAdd(() -> {
                    context.assertTrue(context.getWorld().getBlockState(stone).isAir(), "The stone was broken");
                    context.assertTrue(context.getWorld().getBlockState(guarded).isOf(Blocks.STONE), "The lava-guarded stone is intact");
                    int cobble = ItemMoves.count(f.player().getInventory(), Items.COBBLESTONE);
                    context.assertTrue(cobble == 1, "Its own cobblestone drop was picked up: " + cobble);
                    int pickSlot = -1;
                    for (int s = 0; s < 9; s++) if (f.player().getInventory().getStack(s).isOf(Items.WOODEN_PICKAXE)) pickSlot = s;
                    context.assertTrue(pickSlot >= 0, "The owned pickaxe was moved into the hotbar to mine");
                    context.assertTrue(f.player().getInventory().getStack(pickSlot).getDamage() == 1, "Vanilla tool wear applied");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.kind().equals("native_break") && action.state() == ActionRecord.State.VERIFIED,
                            "Durable ledger records a verified break");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Movement as a player moves (F-10d): a floating course 36 blocks long with a wall across most of it, walked from
     * one end to the other. Sampled every tick: it sprints and sprint-jumps along the open stretches, rounds the wall
     * without zigzagging from block to block, never turns more than 20 degrees in a tick, and gets there faster than
     * a plain sprint. Before F-10d the same walk took 166 to 170 ticks with no jumps, turns of up to 67 degrees a
     * tick and six to eight reversals; with it, 113 to 114 ticks, seven jumps, at most 7.3 degrees and one reversal.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "playerLikeWalk", tickLimit = 600)
    public void aLongWalkSprintsJumpsAndTurnsSmoothly(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos[] origin = new BlockPos[1];
        int[] ticks = new int[1], jumps = new int[1], reversals = new int[1];
        float[] maxTurn = new float[1], lastYaw = new float[1], lastDelta = new float[1];
        boolean[] wasOnGround = new boolean[] {true};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Strider"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = origin[0] = f.player().getBlockPos().up(20).toImmutable();
                    for (int x = 0; x <= 35; x++) for (int z = -6; z <= 6; z++) {
                        world.setBlockState(o.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 4; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = 16; x <= 18; x++) for (int z = -6; z <= 2; z++) for (int y = 0; y <= 1; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 1.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the course"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    lastYaw[0] = f.player().getYaw();
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, origin[0].add(34, 0, 0), 0.8) == 1, "Route requested");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    ticks[0]++;
                    float delta = net.minecraft.util.math.MathHelper.wrapDegrees(player.getYaw() - lastYaw[0]);
                    lastYaw[0] = player.getYaw();
                    maxTurn[0] = Math.max(maxTurn[0], Math.abs(delta));
                    if (Math.abs(delta) > 3 && Math.abs(lastDelta[0]) > 3 && Math.signum(delta) != Math.signum(lastDelta[0])) reversals[0]++;
                    if (Math.abs(delta) > 3) lastDelta[0] = delta;
                    if (wasOnGround[0] && !player.isOnGround() && player.getVelocity().y > 0.2) jumps[0]++;
                    wasOnGround[0] = player.isOnGround();
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_goto"),
                            "Arrives: " + activity + " at " + player.getPos());
                })
                .createAndAdd(() -> {
                    double seconds = ticks[0] / 20.0, metres = 38; // The way round the wall is about 38 blocks.
                    String summary = ticks[0] + " ticks, " + jumps[0] + " jumps, max turn " + maxTurn[0] + " deg a tick, " + reversals[0] + " reversals";
                    KindredMod.LOGGER.info("Strider walk: {}", summary);
                    context.assertTrue(f.player().getBlockPos().getSquaredDistance(origin[0].add(34, 0, 0)) <= 4, "At the far end: " + f.player().getBlockPos());
                    context.assertTrue(metres / seconds > 5.8, "Faster than a plain sprint (5.6 blocks a second): " + summary);
                    context.assertTrue(jumps[0] >= 4, "Sprint-jumps along the open stretches: " + summary);
                    context.assertTrue(maxTurn[0] <= 20f, "Turns ease rather than snap: " + summary);
                    context.assertTrue(reversals[0] <= 3, "No zigzag from block centre to block centre: " + summary);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A route that builds and digs (F-10e): a floating floor with a two-block gap over a long drop, then a stone wall
     * across its whole width, and the goal beyond. Walking round is impossible, so the route bridges the gap with two
     * of the player's own blocks and digs through the wall, one recorded skill at a time, planned again after each.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildRoute", tickLimit = 1200)
    public void aRouteBridgesAGapAndDigsThroughAWall(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] origin = new BlockPos[1];
        int[] cobble = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bridger"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = origin[0] = f.player().getBlockPos().up(20).toImmutable();
                    // Clear well beyond the course: other fixtures' floating courses reach past their plots, and one
                    // left a way round the wall in a suite run.
                    for (int x = -3; x <= 15; x++) for (int z = -7; z <= 7; z++) for (int y = -2; y <= 5; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = -1; x <= 13; x++) for (int z = -2; z <= 2; z++) {
                        if (x == 4 || x == 5) continue; // The gap.
                        world.setBlockState(o.add(x, -1, z), Blocks.STONE.getDefaultState());
                    }
                    for (int z = -2; z <= 2; z++) for (int y = 0; y <= 2; y++) {
                        world.setBlockState(o.add(9, y, z), Blocks.STONE.getDefaultState()); // The wall.
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new ItemStack(Items.COBBLESTONE, 16));
                    cobble[0] = 16;
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the course"))
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos goal = origin[0].add(12, 0, 0);
                    if (player.getBlockPos().getSquaredDistance(goal) <= 1 && player.isOnGround()) return;
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("ACTIVE")) f.runtime.nativeRouteTo(f.source, f.id, goal);
                    context.assertTrue(false, "On the way: " + player.getBlockPos().toShortString() + " " + activity);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertTrue(world.getBlockState(origin[0].add(4, -1, 0)).isOf(Blocks.COBBLESTONE)
                            && world.getBlockState(origin[0].add(5, -1, 0)).isOf(Blocks.COBBLESTONE), "The gap bridged with its own blocks");
                    context.assertTrue(world.getBlockState(origin[0].add(9, 0, 0)).isAir() && world.getBlockState(origin[0].add(9, 1, 0)).isAir(),
                            "A way dug through the wall");
                    // Two spent on the bridge; the two stones dug from the wall drop cobblestone, picked up on the way through.
                    int left = f.player().getInventory().count(Items.COBBLESTONE);
                    context.assertTrue(left >= cobble[0] - 2 && left <= cobble[0], "Two blocks spent, none conjured: " + left);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A break longer than one paid slice, at real time (F-11c): obsidian takes 188 ticks with a diamond pickaxe, 9.4
     * seconds at 20 ticks a second. The test server runs as fast as its ticks allow, so the fixture holds it to 20 a
     * second while it waits, as a live server runs. Before renewal the five-second slice lapsed partway, the skill
     * was halted and the block was left whole (live run 66 lost 85 walks this way).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "realTimeBreak", tickLimit = 800)
    public void aBreakLongerThanOnePaidSliceCompletesAtRealTime(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos obsidian = context.getAbsolutePos(new BlockPos(5, 2, 3));
        long[] started = new long[1];
        long[] lastTick = new long[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Slicer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(obsidian, Blocks.OBSIDIAN.getDefaultState());
                    f.player().getInventory().setStack(15, new ItemStack(Items.DIAMOND_PICKAXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    started[0] = lastTick[0] = System.nanoTime();
                    context.assertTrue(f.runtime.nativeBreak(f.source, f.id, obsidian) == 1, "Obsidian break requested");
                })
                .createAndAdd(() -> {
                    // No faster than a live server: each tick takes at least 50 ms of wall-clock time.
                    long wait = 50_000_000L - (System.nanoTime() - lastTick[0]);
                    if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
                    lastTick[0] = System.nanoTime();
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_break"),
                            "Break completes: " + activity);
                })
                .createAndAdd(() -> {
                    double seconds = (System.nanoTime() - started[0]) / 1e9;
                    context.assertTrue(seconds > 6, "It ran past one five-second slice: " + seconds + " s");
                    context.assertTrue(context.getWorld().getBlockState(obsidian).isAir(), "The obsidian was broken");
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.OBSIDIAN) == 1, "Its own obsidian was picked up");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.kind().equals("native_break") && action.state() == ActionRecord.State.VERIFIED,
                            "Durable ledger records a verified break");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A block over a five-deep shaft: its drop falls to the bottom, and the pickup does not walk over the edge
     * after it. The break still completes and is recorded; the drop stays where it fell.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void breakPickupDoesNotFollowADropDownAShaft(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] feet = new BlockPos[1];
        BlockPos[] target = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Edgewise"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Absolute positions around wherever the player stood up, so the test's rotation does not matter.
                    var world = context.getWorld();
                    feet[0] = f.player().getBlockPos().toImmutable();
                    target[0] = feet[0].east();
                    world.setBlockState(target[0], Blocks.DIRT.getDefaultState());
                    for (int k = 1; k <= 5; k++) world.setBlockState(target[0].down(k), Blocks.AIR.getDefaultState());
                    world.setBlockState(target[0].down(6), Blocks.STONE.getDefaultState());
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeBreak(f.source, f.id, target[0]) == 1, "Break requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_break"),
                            "Break completes: " + activity);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(context.getWorld().getBlockState(target[0]).isAir(), "The dirt was broken");
                    context.assertTrue(player.isAlive() && player.getHealth() >= 20 && player.getBlockY() == feet[0].getY(),
                            "Still on the edge and unhurt: " + player.getBlockPos() + " health " + player.getHealth());
                    context.assertTrue(ItemMoves.count(player.getInventory(), Items.DIRT) == 0, "The drop was left at the bottom");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.kind().equals("native_break") && action.state() == ActionRecord.State.VERIFIED,
                            "Durable ledger records a verified break: " + action);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
