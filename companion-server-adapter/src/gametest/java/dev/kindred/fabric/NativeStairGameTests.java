package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A staircase built up as the player walks (F-10h). On a course in the air with no way up but its own blocks, a route
 * to a ledge ten blocks up and ten on is climbed by a flight of stairs: each block set in the air under the feet,
 * against the wall alongside, and landed on in the same jump. Every block is its own verified record, the flight is
 * one task, and the player never stands still between the first step and the last.
 */
public final class NativeStairGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "stairWall", tickLimit = 1400)
    public void aWallTenHighIsClimbedByAStaircaseInOneMovement(TestContext context) {
        climb(context, true, 10);
    }

    /**
     * In the open, with no wall or ground beside a step to set its block against, each step after the first takes a
     * filler block under it first, set from the step below against the block the player stands on.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "stairOpen", tickLimit = 1400)
    public void inTheOpenEachStepIsSetOnAFillerBlock(TestContext context) {
        climb(context, false, 6);
    }

    private static void climb(TestContext context, boolean wall, int height) {
        Set<UUID> flights = ConcurrentHashMap.newKeySet();
        AtomicInteger verified = new AtomicInteger();
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> new ActionAdmission.Writer() {
            @Override public CompletableFuture<ActionRecord> prepare(ActionRecord intent) {
                if ("native_stair".equals(intent.kind())) flights.add(intent.taskId());
                return writer.prepare(intent);
            }
            @Override public CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) {
                if ("native_stair".equals(next.kind()) && next.state() == ActionRecord.State.VERIFIED) verified.incrementAndGet();
                return writer.transition(next, expectedRevision);
            }
        }, true);
        BlockPos[] origin = new BlockPos[1];
        int[] cobble = {32};
        // The first tick a step's block is in, the tick the top step is reached, the longest standing still between.
        long[] first = {-1}, top = {-1};
        int[] still = {0}, longestStill = {0};
        String name = wall ? "Climber" : "Opener";
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = origin[0] = f.player().getBlockPos().up(20).toImmutable();
                    for (int x = -3; x <= height + 6; x++) for (int z = -5; z <= 5; z++) for (int y = -3; y <= height + 4; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // A pad to start from, a ledge up ahead, and with {@code wall} a wall alongside the way up.
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.setBlockState(o.add(x, -1, z), Blocks.STONE.getDefaultState());
                    for (int x = height + 1; x <= height + 4; x++) for (int z = -1; z <= 0; z++) {
                        world.setBlockState(o.add(x, height - 1, z), Blocks.STONE.getDefaultState());
                    }
                    if (wall) for (int x = 0; x <= height + 2; x++) for (int y = -1; y <= height; y++) world.setBlockState(o.add(x, y, 1), Blocks.STONE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new ItemStack(Items.COBBLESTONE, cobble[0]));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the pad"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var world = context.getWorld();
                    long now = world.getTime();
                    if (first[0] < 0 && player.getInventory().count(Items.COBBLESTONE) < cobble[0]) first[0] = now;
                    if (first[0] >= 0 && top[0] < 0) {
                        if (player.getBlockY() >= origin[0].getY() + height) {
                            top[0] = now;
                        } else {
                            var velocity = player.getVelocity();
                            if (player.isOnGround() && velocity.x * velocity.x + velocity.z * velocity.z < 1e-4) still[0]++;
                            else still[0] = 0;
                            longestStill[0] = Math.max(longestStill[0], still[0]);
                        }
                    }
                    BlockPos goal = origin[0].add(height + 3, height, 0);
                    if (player.getBlockPos().getSquaredDistance(goal) <= 1 && player.isOnGround()) return;
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("ACTIVE")) f.runtime.nativeRouteTo(f.source, f.id, goal);
                    context.assertTrue(false, "On the way: " + player.getBlockPos().toShortString() + " " + activity);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // What it built: every cobblestone block on the course; a filler is one with a step on it.
                    var placed = new java.util.ArrayList<BlockPos>();
                    for (int x = -3; x <= height + 6; x++) for (int z = -5; z <= 5; z++) for (int y = -3; y <= height + 4; y++) {
                        BlockPos at = origin[0].add(x, y, z);
                        if (world.getBlockState(at).isOf(Blocks.COBBLESTONE)) placed.add(at.toImmutable());
                    }
                    long fillers = placed.stream().filter(at -> placed.contains(at.up())).count();
                    long steps = placed.size() - fillers;
                    int spent = cobble[0] - f.player().getInventory().count(Items.COBBLESTONE);
                    KindredMod.LOGGER.info("{}: {} steps and {} fillers of cobblestone, {} spent, {} verified records in {} task(s); "
                                    + "the climb took {} ticks, the longest standing still {} ticks", name, steps, fillers, spent,
                            verified.get(), flights.size(), top[0] - first[0], longestStill[0]);
                    // The ledge's own edge is the last step up, walked onto, so a flight is one step short of the height.
                    context.assertTrue(steps >= height - 1, "A block for every step: " + steps + " for a climb of " + height);
                    if (wall) {
                        context.assertTrue(fillers == 0, "No filler where the wall is there to set each step against: " + fillers);
                        context.assertTrue(placed.stream().allMatch(at -> world.getBlockState(at.south()).isOf(Blocks.STONE)),
                                "Every step set against the wall: " + placed);
                    } else {
                        context.assertTrue(fillers >= height - 3, "Fillers under the steps in the open: " + fillers);
                    }
                    context.assertTrue(spent == placed.size(), "Exactly the blocks placed spent, none conjured: " + spent + " for " + placed.size());
                    context.assertTrue(verified.get() == placed.size(), "Every block its own verified record: " + verified.get());
                    context.assertTrue(flights.size() == 1, "One flight, one task: " + flights.size());
                    context.assertTrue(top[0] - first[0] <= 20L * height, "Climbed at a step a second or better: " + (top[0] - first[0]) + " ticks");
                    context.assertTrue(longestStill[0] <= 2, "No stop between steps: stood still " + longestStill[0] + " ticks");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
