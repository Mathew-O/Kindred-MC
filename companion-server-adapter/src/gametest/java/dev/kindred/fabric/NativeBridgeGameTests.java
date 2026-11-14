package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In plain terms: GameTests of an AI player bridging a gap the way a practised player does (E-71a): its back to the gap,
 * looking down at the edge, walking backward upright and crouching only as its feet reach the edge, placing the block
 * the moment its look meets the side of the block it stands on, and standing up again as the block lands. Over a drop
 * that could kill it, with a mob near or when hit, it keeps the crouch the whole way instead.
 *
 * Technical: {@code native_bridge} on a course in the air: a pad, a gap twelve blocks long and a pad beyond. The pit
 * under the gap is three deep, a fall it survives, so the crouch is timed; over lava with no bucket, or with a hostile
 * within eight blocks, it is held. Every block is its own verified record and exactly one cobblestone is spent on it. A
 * placement refused part way (a cell claimed by another task) ends the run with the player crouched on the last block,
 * never in the pit. The pace of the timed crouch is measured against the held crouch on the same course.
 */
public final class NativeBridgeGameTests implements FabricGameTest {
    /** The gap's length, in blocks. */
    private static final int GAP = 12;

    /**
     * Twelve blocks over a three-deep pit, first with the crouch held all the way and then, on the same course rebuilt,
     * with it timed: twelve blocks and twelve verified records each time, no fall, the timed run sneaking for under half
     * of its ticks and at least 1.7 times as fast as the held one.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bridgePace", tickLimit = 2400)
    public void twelveBlocksOverAThreeDeepPitAreBridgedWithTheCrouchTimedAtEachEdge(TestContext context) {
        var run = new Run(context);
        var held = new Measure();
        var timed = new Measure();
        run.f.runnerAwaitingReady(context, "Ready")
                .createAndAdd(() -> run.f.runtime.createNative(run.f.source, "Bridger"))
                .createAndAdd(() -> run.f.established(context))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> run.build(Floor.STONE, 16, false))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "On the pad"))
                .createAndAdd(() -> run.start("held", held))
                .createAndAdd(() -> run.follow(held))
                .createAndAdd(() -> {
                    run.expectCompleted(held);
                    run.build(Floor.STONE, 16, false);
                })
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "Back on the pad"))
                .createAndAdd(() -> run.start("auto", timed))
                .createAndAdd(() -> run.follow(timed))
                .createAndAdd(() -> {
                    run.expectCompleted(timed);
                    KindredMod.LOGGER.info("Bridge pace over {} blocks: held {} ticks ({} sneaking), timed {} ticks ({} sneaking), {}x",
                            GAP, held.ticks, held.sneaking, timed.ticks, timed.sneaking, String.format("%.2f", held.ticks / (double) timed.ticks));
                    context.assertTrue(held.sneaking >= held.ticks - 1, "The held crouch held throughout: " + held.sneaking + " of " + held.ticks);
                    context.assertTrue(timed.sneaking * 2 < timed.ticks, "Sneaking for under half the timed run: " + timed.sneaking + " of " + timed.ticks);
                    context.assertTrue(held.ticks >= 1.7 * timed.ticks, "The timed crouch at least 1.7 times the held crouch's pace: held "
                            + held.ticks + ", timed " + timed.ticks);
                    run.f.runtime.stop();
                })
                .completeIfSuccessful();
    }

    /**
     * The sixth cell claimed by another task: the run stops with five blocks placed, the player crouched at the edge of
     * the fifth and standing on it, never in the pit, and the run's end says why and where.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bridgeRefused", tickLimit = 1600)
    public void aBlockRefusedMidRunLeavesItCrouchedAtTheEdgeNotInThePit(TestContext context) {
        var run = new Run(context);
        var m = new Measure();
        run.f.runnerAwaitingReady(context, "Ready")
                .createAndAdd(() -> run.f.runtime.createNative(run.f.source, "Refused"))
                .createAndAdd(() -> run.f.established(context))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    run.build(Floor.STONE, 16, false);
                    // Another task holds the sixth cell, as a player's claim on it would.
                    BlockPos sixth = run.cell(6);
                    String key = NativeBridgeAction.claimKey(run.f.runtime.nativeWorldIdForTest(run.f.id),
                            context.getWorld().getRegistryKey().getValue().toString(), sixth);
                    context.assertTrue(run.f.runtime.claims().acquire(UUID.randomUUID(), UUID.randomUUID(), List.of(key),
                            System.currentTimeMillis(), 240_000).isPresent(), "The sixth cell claimed by another task");
                })
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "On the pad"))
                .createAndAdd(() -> run.start("auto", m))
                .createAndAdd(() -> run.follow(m))
                .createAndAdd(() -> {
                    var player = run.f.player();
                    var result = run.f.runtime.nativeLastResult(run.f.id);
                    KindredMod.LOGGER.info("Refused bridge: {} at {} ({}), {} verified", result, player.getPos(), m.state, run.verified.get());
                    context.assertTrue("BLOCKED".equals(m.state), "The run ended refused: " + m.state);
                    context.assertTrue("claim".equals(result.get("reason")), "Refused for the claim: " + result);
                    context.assertTrue(run.cell(6).toShortString().equals(result.get("stopped_at")), "Stopped at the sixth cell: " + result);
                    context.assertTrue("true".equals(result.get("at_edge")) && "true".equals(result.get("crouched")),
                            "Crouched at the edge when it was refused, and still crouched as the run ended: " + result);
                    context.assertTrue(run.verified.get() == 5 && run.placedCells().size() == 5, "Five blocks placed and verified: "
                            + run.verified.get() + ", " + run.placedCells());
                    context.assertTrue("5".equals(result.get("blocks")) && run.cell(1).toShortString().equals(result.get("placed_first"))
                            && run.cell(5).toShortString().equals(result.get("placed_last")), "The five placed cells reported: " + result);
                    context.assertTrue(!m.fell && m.endOnGround && Math.floor(m.endPos.y) == run.origin.getY(), "On the bridge, not in the pit: "
                            + m.endPos);
                    // The fifth block spans five to six along; the player hangs up to three tenths past its edge.
                    double along = m.endPos.x - run.origin.getX();
                    context.assertTrue(along > 5.5 && along < 6.31, "On the fifth block, at its edge: " + along);
                    m.endTick = context.getTick();
                })
                .createAndAdd(() -> {
                    // Letting go of the sneak did not carry it off the edge: a second on, it is on the bridge still.
                    var player = run.f.player();
                    context.assertTrue(context.getTick() >= m.endTick + 20, "A second after the run");
                    context.assertTrue(player.getY() >= run.origin.getY() - 0.01, "Still on the bridge a second after: " + player.getPos());
                    run.f.runtime.stop();
                })
                .completeIfSuccessful();
    }

    /** Lava under the gap and no bucket: the crouch is held for the whole run, and the twelve blocks still go in. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bridgeLava", tickLimit = 2000)
    public void overLavaWithNoBucketItHoldsTheCrouchThroughout(TestContext context) {
        var run = new Run(context);
        var m = new Measure();
        run.f.runnerAwaitingReady(context, "Ready")
                .createAndAdd(() -> run.f.runtime.createNative(run.f.source, "Careful"))
                .createAndAdd(() -> run.f.established(context))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> run.build(Floor.LAVA, 16, false))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "On the pad"))
                .createAndAdd(() -> run.start("auto", m))
                .createAndAdd(() -> run.follow(m))
                .createAndAdd(() -> {
                    run.expectCompleted(m);
                    KindredMod.LOGGER.info("Over lava: {} ticks, {} sneaking", m.ticks, m.sneaking);
                    context.assertTrue(m.sneaking >= m.ticks - 1, "Crouched throughout over lava: " + m.sneaking + " of " + m.ticks);
                    run.f.runtime.stop();
                })
                .completeIfSuccessful();
    }

    /**
     * Lava under the gap with a water bucket in the hotbar: water does not save a fall into lava, so the crouch is held for
     * the whole run all the same (MODES' review of #263).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bridgeLavaBucket", tickLimit = 2000)
    public void overLavaWithAWaterBucketItStillHoldsTheCrouchThroughout(TestContext context) {
        var run = new Run(context);
        var m = new Measure();
        run.f.runnerAwaitingReady(context, "Ready")
                .createAndAdd(() -> run.f.runtime.createNative(run.f.source, "Wise"))
                .createAndAdd(() -> run.f.established(context))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> run.build(Floor.LAVA, 16, true))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "On the pad"))
                .createAndAdd(() -> run.start("auto", m))
                .createAndAdd(() -> run.follow(m))
                .createAndAdd(() -> {
                    run.expectCompleted(m);
                    KindredMod.LOGGER.info("Over lava with a bucket: {} ticks, {} sneaking", m.ticks, m.sneaking);
                    context.assertTrue(m.sneaking >= m.ticks - 1, "Crouched throughout over lava, bucket or not: " + m.sneaking + " of " + m.ticks);
                    run.f.runtime.stop();
                })
                .completeIfSuccessful();
    }

    /** A zombie brought within eight blocks after the fourth block: from then on the crouch is held. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bridgeZombie", tickLimit = 2000)
    public void aZombieWithinEightMidRunSwitchesItToTheHeldCrouch(TestContext context) {
        var run = new Run(context);
        var m = new Measure();
        int[] after = {0}, sneakingAfter = {0};
        long[] spawnedAt = {-1};
        run.f.runnerAwaitingReady(context, "Ready")
                .createAndAdd(() -> run.f.runtime.createNative(run.f.source, "Wary"))
                .createAndAdd(() -> run.f.established(context))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> run.build(Floor.STONE, 16, false))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "On the pad"))
                .createAndAdd(() -> run.start("auto", m))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    if (spawnedAt[0] < 0 && run.verified.get() >= 4) {
                        // On a block of its own beside the gap, in plain view, still: within eight of the player.
                        BlockPos stand = run.origin.add(7, -1, 3);
                        world.setBlockState(stand, Blocks.STONE.getDefaultState());
                        var zombie = new ZombieEntity(EntityType.ZOMBIE, world);
                        zombie.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY() + 1, stand.getZ() + 0.5, 0, 0);
                        zombie.setAiDisabled(true);
                        zombie.setPersistent();
                        world.spawnEntity(zombie);
                        spawnedAt[0] = world.getTime();
                    }
                    // A tick for it to be seen; every tick after it, crouched.
                    if (spawnedAt[0] >= 0 && world.getTime() > spawnedAt[0] + 1 && m.state == null) {
                        after[0]++;
                        if (run.f.player().isSneaking()) sneakingAfter[0]++;
                    }
                    run.follow(m);
                })
                .createAndAdd(() -> {
                    run.expectCompleted(m);
                    KindredMod.LOGGER.info("Zombie mid-run: {} ticks ({} sneaking); after it {} ticks, {} sneaking", m.ticks, m.sneaking,
                            after[0], sneakingAfter[0]);
                    context.assertTrue(spawnedAt[0] >= 0 && after[0] > 10, "Ticks bridged with the zombie about: " + after[0]);
                    context.assertTrue(sneakingAfter[0] >= after[0] - 1, "Held crouch once the zombie was within eight: " + sneakingAfter[0]
                            + " of " + after[0]);
                    context.assertTrue(m.sneaking < m.ticks, "Timed before the zombie: " + m.sneaking + " of " + m.ticks);
                    run.f.runtime.stop();
                })
                .completeIfSuccessful();
    }

    /**
     * A route to the far pad over a pit twenty deep, too deep to climb down into and out of, with a water bucket in the
     * hotbar for the clutch: the route's bridge steps are taken as one run of {@code native_bridge}, with the crouch
     * timed, and the player walks off onto the far pad.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bridgeRoute", tickLimit = 2400)
    public void aRouteOverADeepPitIsBridgedAsOneRun(TestContext context) {
        var run = new Run(context);
        var m = new Measure();
        run.f.runnerAwaitingReady(context, "Ready")
                .createAndAdd(() -> run.f.runtime.createNative(run.f.source, "Router"))
                .createAndAdd(() -> run.f.established(context))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> run.build(Floor.DEEP, 16, true))
                .createAndAdd(() -> context.assertTrue(run.f.player().isOnGround(), "On the pad"))
                .createAndAdd(() -> {
                    var player = run.f.player();
                    BlockPos goal = run.origin.add(GAP + 2, 0, 0);
                    m.tick(run, player);
                    if (player.getBlockPos().getSquaredDistance(goal) <= 1 && player.isOnGround()) return;
                    var activity = run.f.runtime.nativeActivityStatus(Actor.console(), run.f.id);
                    if (!activity.phase().equals("ACTIVE")) run.f.runtime.nativeRouteTo(run.f.source, run.f.id, goal);
                    context.assertTrue(false, "On the way: " + player.getBlockPos().toShortString() + " " + activity);
                })
                .createAndAdd(() -> {
                    var placed = run.placedCells();
                    KindredMod.LOGGER.info("Route over the deep pit: {} blocks in {} bridge task(s), {} verified; {} ticks, {} sneaking", placed.size(),
                            run.tasks.size(), run.verified.get(), m.ticks, m.sneaking);
                    context.assertTrue(!m.fell, "No fall into the pit");
                    context.assertTrue(placed.size() == GAP && run.verified.get() == GAP, "A block for each cell of the gap: " + placed);
                    context.assertTrue(run.tasks.size() == 1, "The gap bridged as one run: " + run.tasks.size() + " task(s)");
                    context.assertTrue(run.spent() == GAP, "Exactly the blocks placed spent: " + run.spent());
                    context.assertTrue(m.sneaking * 2 < m.ticks, "The crouch timed, with the bucket in the hotbar: " + m.sneaking + " of " + m.ticks);
                    run.f.runtime.stop();
                })
                .completeIfSuccessful();
    }

    private enum Floor { STONE, LAVA, DEEP }

    /** What one run did, tick by tick while it ran. */
    private static final class Measure {
        int ticks, sneaking;
        boolean fell;
        String state, task, before;
        boolean begun, endOnGround;
        Vec3d endPos;
        long endTick;

        void tick(Run run, net.minecraft.server.network.ServerPlayerEntity player) {
            // Begun once it has moved off the middle of the pad's last block, where it stands while the task is admitted.
            if (run.verified.get() == 0 && !begun && player.getX() - run.origin.getX() < 0.55) return;
            begun = true;
            // The last block in and the player still: the run is over, its records closing, the sneak let go.
            var velocity = player.getVelocity();
            if (run.verified.get() == GAP && velocity.x * velocity.x + velocity.z * velocity.z < 1e-6) return;
            ticks++;
            if (player.isSneaking()) sneaking++;
            if (player.getY() < run.origin.getY() - 0.5) fell = true;
        }
    }

    /** The course, the player and what was recorded. */
    private static final class Run {
        final TestContext context;
        final NativePaidSkillsGameTests.Fixture f;
        final Set<UUID> tasks = ConcurrentHashMap.newKeySet();
        final AtomicInteger verified = new AtomicInteger();
        BlockPos origin;
        int cobble;

        Run(TestContext context) {
            this.context = context;
            this.f = new NativePaidSkillsGameTests.Fixture(context, writer -> new ActionAdmission.Writer() {
                @Override public CompletableFuture<ActionRecord> prepare(ActionRecord intent) {
                    if ("native_bridge".equals(intent.kind())) tasks.add(intent.taskId());
                    return writer.prepare(intent);
                }
                @Override public CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) {
                    if ("native_bridge".equals(next.kind()) && next.state() == ActionRecord.State.VERIFIED) verified.incrementAndGet();
                    return writer.transition(next, expectedRevision);
                }
            }, true);
        }

        /** The {@code i}th cell of the bridge, one to twelve, at the pads' level. */
        BlockPos cell(int i) { return origin.add(i, -1, 0); }

        /**
         * The course high in the air: a pad, the gap with its pit floor (three deep, or lava, or twenty deep), the far pad;
         * the player set on the pad's last block, its back to the gap, with {@code blocks} cobblestone in the first slot.
         */
        void build(Floor floor, int blocks, boolean bucket) {
            var world = context.getWorld();
            var player = f.player();
            if (origin == null) origin = player.getBlockPos().up(floor == Floor.DEEP ? 30 : 12).toImmutable();
            BlockPos o = origin;
            int depth = floor == Floor.DEEP ? 21 : 4;
            for (int x = -4; x <= GAP + 5; x++) for (int z = -4; z <= 4; z++) for (int y = -depth - 1; y <= 4; y++) {
                world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
            }
            for (int x = -3; x <= 0; x++) for (int z = -1; z <= 1; z++) world.setBlockState(o.add(x, -1, z), Blocks.STONE.getDefaultState());
            for (int x = GAP + 1; x <= GAP + 4; x++) for (int z = -1; z <= 1; z++) world.setBlockState(o.add(x, -1, z), Blocks.STONE.getDefaultState());
            // The pit's floor, with a rim round lava to hold it.
            for (int x = 0; x <= GAP + 1; x++) for (int z = -2; z <= 2; z++) {
                boolean rim = x == 0 || x == GAP + 1 || Math.abs(z) == 2;
                world.setBlockState(o.add(x, -depth - 1, z), Blocks.STONE.getDefaultState());
                world.setBlockState(o.add(x, -depth, z), floor == Floor.LAVA && !rim ? Blocks.LAVA.getDefaultState() : Blocks.STONE.getDefaultState());
            }
            player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90, 0);
            player.setVelocity(Vec3d.ZERO);
            player.setHealth(player.getMaxHealth());
            var inventory = player.getInventory();
            for (int s = 0; s < 36; s++) inventory.setStack(s, ItemStack.EMPTY);
            inventory.setStack(0, new ItemStack(Items.COBBLESTONE, blocks));
            if (bucket) inventory.setStack(2, new ItemStack(Items.WATER_BUCKET));
            cobble = blocks;
            verified.set(0);
            tasks.clear();
            f.runtime.nativeLifecycle(f.source, "resume", f.id);
        }

        /** Sets a run going from the pad's last block, east over the gap, with the given crouch. */
        void start(String crouch, Measure m) {
            BlockPos feet = f.player().getBlockPos();
            context.assertTrue(feet.equals(origin), "On the pad's last block: " + feet.toShortString());
            Map<String, String> args = new HashMap<>(Map.of("x", String.valueOf(feet.getX()), "y", String.valueOf(feet.getY()),
                    "z", String.valueOf(feet.getZ()), "dir", "east", "n", String.valueOf(GAP), "slot", "0"));
            args.put("crouch", crouch);
            // The task before it, as the runner shows it now: the run is the next one it shows.
            m.before = f.runtime.nativeTaskForTest(f.id)[0];
            context.assertTrue(f.runtime.nativeTaskForFixtures(f.source, f.id, "native_bridge", args) == 1, "The bridge taken up");
        }

        /** Measures the run each tick until it ends; its end state is kept on the measure. */
        void follow(Measure m) {
            var player = f.player();
            if (m.state != null) return;
            m.tick(this, player);
            String[] task = f.runtime.nativeTaskForTest(f.id);
            if (m.task == null && !task[0].isEmpty() && !task[0].equals(m.before)) m.task = task[0];
            // Closed, the runner lets its task go: the run's own, or none, with the runner at an end.
            boolean ended = m.task != null && (task[0].isEmpty() || m.task.equals(task[0])) && Set.of("COMPLETED", "BLOCKED", "CANCELLED").contains(task[1]);
            if (!ended) context.assertTrue(false, "Bridging: " + player.getPos() + " " + task[1]);
            m.state = task[1];
            // Where it stood as the run ended, before anything else it does.
            m.endPos = player.getPos();
            m.endOnGround = player.isOnGround();
        }

        void expectCompleted(Measure m) {
            var player = f.player();
            context.assertTrue("COMPLETED".equals(m.state), "The run completed: " + m.state + " " + f.runtime.nativeLastResult(f.id));
            context.assertTrue(!m.fell, "No fall into the pit: " + player.getPos());
            var placed = placedCells();
            context.assertTrue(placed.size() == GAP, "Twelve blocks, one in each cell: " + placed);
            for (int i = 1; i <= GAP; i++) context.assertTrue(placed.contains(cell(i)), "A block in cell " + i + ": " + placed);
            context.assertTrue(verified.get() == GAP, "Twelve verified records: " + verified.get());
            context.assertTrue(tasks.size() == 1, "One task: " + tasks.size());
            context.assertTrue(spent() == GAP, "Exactly the blocks placed spent, none conjured: " + spent());
            context.assertTrue(m.endOnGround && Math.floor(m.endPos.y) == origin.getY() && m.endPos.x - origin.getX() > GAP - 0.5,
                    "On the last block: " + m.endPos);
        }

        List<BlockPos> placedCells() {
            var world = context.getWorld();
            var out = new java.util.ArrayList<BlockPos>();
            for (int x = -4; x <= GAP + 5; x++) for (int z = -4; z <= 4; z++) for (int y = -22; y <= 4; y++) {
                if (world.getBlockState(origin.add(x, y, z)).isOf(Blocks.COBBLESTONE)) out.add(origin.add(x, y, z).toImmutable());
            }
            return out;
        }

        int spent() { return cobble - f.player().getInventory().count(Items.COBBLESTONE); }
    }
}
