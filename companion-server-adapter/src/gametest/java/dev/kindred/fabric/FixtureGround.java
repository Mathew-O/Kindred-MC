package dev.kindred.fabric;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Ground a fixture lays put back as it found it when the fixture ends, and the animals and items left on it taken
 * away. The test server keeps whatever a test changed, so the field one fixture laid lay under the tests placed there
 * after it, and its animals wandered on into them: a ranch went after a cow on ground another had raised above its
 * pen, and penned a cow not its own (full run 20 on 2026-10-24). A fixture that fails is put back as its test ends: its
 * field left laid lay under the next test placed beside it (a failed bring left its hall and line where the pit test
 * after it stood, full run 53 on 2026-10-29).
 */
final class FixtureGround {
    /**
     * Test structures of empty ground, as wide as a fixture's field: the test server spaces its tests by their
     * structures and runs several at once, so a fixture that lays ground wider than its structure laid it over another
     * running beside it (the night fixture and the repair fixture, two players called Mason, on one field). The server
     * also clears a test's structure, keeps it loaded while it runs, and walls it round with barriers, which keep its
     * players and animals on its own field; with sky access, as these fixtures ask, it puts no barrier over it, which
     * over a structure this wide shut its whole field from the sky ("No flat open ground for a cabin"). {@link #WIDE}
     * is 120 by 120 and 40 high, {@link #FIELD} 80 by 80 and 32 high; a fixture's origin is put in from the
     * structure's corner as far as its field runs the other way.
     */
    static final String WIDE = "kindred_gametest:wide", FIELD = "kindred_gametest:field";

    private record Kept(net.minecraft.server.world.ServerWorld world, BlockPos min, BlockPos max, BlockState[] states, Shell shell) { }

    /**
     * How far round a kept box, each way, its fluid shell runs (F-22c111): still water spreads seven blocks on flat
     * ground, so a fluid that ran out past a box's edge, or fell from it and spread where it landed, lies within eight.
     */
    static final int SHELL = 8;

    /**
     * The fluid round a kept box as it was when kept (F-22c111): from {@link #SHELL} out each way and from the box's top
     * down to the world's floor, the cells outside the box that held a fluid, and the chunks loaded then (a chunk not
     * loaded is not known, and nothing in it is cleared).
     */
    private record Shell(BlockPos min, BlockPos max, java.util.Set<Long> fluid, java.util.Set<Long> chunks) { }

    /** Every box each test keeps, in the order kept: a test that keeps two (a pad and a floor) gets both back. */
    private static final Map<TestContext, java.util.List<Kept>> KEPT = Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * The chunks of a kept box held loaded, but not ticked, until it is put back (F-22c75b): a fixture that let go of
     * the chunks it forced as it ended had its chunks unloaded before its ground was put back, and the drops left on it
     * went unseen, then came back as a later test nearby loaded them (a raw chicken by the arena of the crash test,
     * "Nothing dropped"). At this level a chunk's entities are loaded, so the restore finds them, and none of them tick.
     */
    private static final net.minecraft.server.world.ChunkTicketType<net.minecraft.util.math.ChunkPos> HELD =
            net.minecraft.server.world.ChunkTicketType.create("kindred_fixture_ground", java.util.Comparator.comparingLong(net.minecraft.util.math.ChunkPos::toLong));

    private FixtureGround() { }

    private static void held(net.minecraft.server.world.ServerWorld world, BlockPos min, BlockPos max, boolean held) {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                var chunk = new net.minecraft.util.math.ChunkPos(cx, cz);
                if (held) world.getChunkManager().addTicket(HELD, chunk, 0, chunk);
                else world.getChunkManager().removeTicket(HELD, chunk, 0, chunk);
            }
        }
    }

    /**
     * The chunks from {@code min} to {@code max} kept simulated while the test runs. The test server sets no simulation
     * distance, so a player keeps entities moving only in the chunk it stands in, and the server only the chunk of a
     * test's own structure: a villager by a chunk's edge stopped ticking when the player beside it stepped over into
     * the next (it never let go of a job whose lectern was gone), and a minecart rolling out of the player's chunk
     * stopped dead. A server with a simulation distance of its own keeps them moving. The test server releases what is
     * forced when the batch ends.
     */
    static void simulated(TestContext context, BlockPos min, BlockPos max) {
        var world = context.getWorld();
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) world.setChunkForced(cx, cz, true);
        }
    }

    /**
     * A hostile mob that acts as in a live world, at {@code relative} (F-22c80). {@link TestContext#spawnMob} clears
     * every goal and task of the mob it spawns: a zombie told {@code setTarget(player)} keeps its target and stands where
     * it spawned (0 blows in 300 ticks, 7 blocks off), where one spawned here closes in and strikes (8 blows). A fixture
     * whose mob is to come at the player spawns it here; one that wants it still keeps {@code spawnMob} or turns its AI
     * off. Persistent, so it never despawns mid-test.
     */
    static <E extends net.minecraft.entity.mob.MobEntity> E hostile(TestContext context, net.minecraft.entity.EntityType<E> type,
                                                                     net.minecraft.util.math.Vec3d relative) {
        E mob = context.spawnEntity(type, relative);
        mob.setPersistent();
        return mob;
    }

    /** What lies from {@code min} to {@code max} now, to be put back by {@link #restore}. */
    static void keep(TestContext context, BlockPos min, BlockPos max) {
        keep(context, context.getWorld(), min, max);
    }

    /**
     * What lies from {@code min} to {@code max} in {@code world} now, to be put back by {@link #restore}: the ground a
     * test lays in another dimension than its own, its nether's, as much its own to put back as its overworld field
     * (F-22c75b).
     */
    static void keep(TestContext context, net.minecraft.server.world.ServerWorld world, BlockPos min, BlockPos max) {
        int dx = max.getX() - min.getX() + 1, dy = max.getY() - min.getY() + 1, dz = max.getZ() - min.getZ() + 1;
        var states = new BlockState[dx * dy * dz];
        var at = new BlockPos.Mutable();
        int i = 0;
        for (int x = 0; x < dx; x++) for (int y = 0; y < dy; y++) for (int z = 0; z < dz; z++) {
            states[i++] = world.getBlockState(at.set(min.getX() + x, min.getY() + y, min.getZ() + z));
        }
        // A second box kept by the same test once replaced the first, which was then never put back (F-22c75b).
        var boxes = KEPT.computeIfAbsent(context, c -> Collections.synchronizedList(new java.util.ArrayList<>()));
        var shellNow = shell(world, min, max);
        boxes.add(new Kept(world, min.toImmutable(), max.toImmutable(), states, shellNow));
        held(world, min, max, true);
        if (world == context.getWorld()) FixtureBounds.kept(stateOf(context), min, max);
        else FixtureBounds.kept(stateOf(context), world.getRegistryKey(), min, max);
        if (boxes.size() == 1) whenDone(context, () -> restore(context));
    }

    /**
     * {@code action} run as the test ends, passed or failed (once a test is done the test server ticks nothing it
     * scheduled, its own clean-up steps included).
     */
    static void whenDone(TestContext context, Runnable action) {
        try {
            var field = TestContext.class.getDeclaredField("test");
            field.setAccessible(true);
            ((net.minecraft.test.GameTestState) field.get(context)).addListener(new net.minecraft.test.TestListener() {
                @Override public void onStarted(net.minecraft.test.GameTestState test) { }
                @Override public void onPassed(net.minecraft.test.GameTestState test, net.minecraft.test.TestRunContext run) { action.run(); }
                @Override public void onFailed(net.minecraft.test.GameTestState test, net.minecraft.test.TestRunContext run) { action.run(); }
                @Override public void onRetry(net.minecraft.test.GameTestState previous, net.minecraft.test.GameTestState next,
                                              net.minecraft.test.TestRunContext run) { action.run(); }
            });
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            KindredMod.LOGGER.warn("A fixture cannot act as its test ends: {}", unavailable.toString());
        }
    }

    /**
     * Every block kept put back, without updates to its neighbours, then everything on the ground but players taken
     * away: animals, mobs, and the items a container put back scattered. What lies in another test's plot is left as
     * it is (F-22c75b): the server places the next batch within the tick that ends this one, before this test's own
     * listeners run, and the tests of a batch run side by side, so a box kept past this test's plot held cells that
     * are now a neighbour's, its barriers, its ground and its animals among them.
     */
    static void restore(TestContext context) {
        var boxes = KEPT.remove(context);
        if (boxes == null) return;
        var self = stateOf(context);
        var plots = FixtureBounds.othersPlots(self);
        // The ground a live neighbour keeps is as much its own as its plot (F-22c75b): left as it is, and counted apart.
        var keptHere = FixtureBounds.othersKept(self);
        int left = 0, spared = 0, leftKept = 0, sparedKept = 0;
        // The last kept first: a box kept after the fixture laid ground over an earlier one holds that ground, and the
        // earlier box what was there before it.
        for (int k = boxes.size() - 1; k >= 0; k--) {
            var kept = boxes.get(k);
            var world = kept.world();
            // In another world than the test's own: no plot of any test's there, only the ground live tests keep there.
            boolean home = world == context.getWorld();
            var others = home ? plots : java.util.List.<Box>of();
            var othersKept = home ? keptHere : FixtureBounds.othersKept(self, world.getRegistryKey());
            var min = kept.min();
            var max = kept.max();
            int dx = max.getX() - min.getX() + 1, dy = max.getY() - min.getY() + 1, dz = max.getZ() - min.getZ() + 1;
            var at = new BlockPos.Mutable();
            int i = 0;
            for (int x = 0; x < dx; x++) for (int y = 0; y < dy; y++) for (int z = 0; z < dz; z++) {
                var state = kept.states()[i++];
                at.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                if (world.getBlockState(at) == state) continue;
                if (within(others, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5)) {
                    left++;
                    continue;
                }
                if (within(othersKept, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5)) {
                    leftKept++;
                    continue;
                }
                world.setBlockState(at, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
            }
            // The fluid that ran out of the box while the test ran, cleared (F-22c111): put back without updates, the box
            // never tells the fluid outside it to drain, and the open-water test's sea, run out past its edge and fallen
            // below it, lay on BuildBattle's walkway in the next batch ("Nobody voted", CI runs 44918551355, 43782657628).
            clearSpilled(context, self, boxes, kept, others, othersKept);
            var box = new Box(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 8, max.getZ() + 1);
            for (Entity entity : world.getEntitiesByClass(Entity.class, box, e -> !(e instanceof PlayerEntity))) {
                if (within(others, entity.getX(), entity.getY(), entity.getZ())) spared++;
                else if (within(othersKept, entity.getX(), entity.getY(), entity.getZ())) sparedKept++;
                else entity.discard();
            }
            held(world, min, max, false);
        }
        if (left > 0 || spared > 0) {
            KindredMod.LOGGER.info("{}: its ground put back but {} blocks and {} entities within another test's plot left as they are",
                    self == null ? "A fixture" : self.getTemplatePath(), left, spared);
        }
        if (leftKept > 0 || sparedKept > 0) {
            KindredMod.LOGGER.info("{}: its ground put back but {} blocks and {} entities within ground a live test keeps left as they are",
                    self == null ? "A fixture" : self.getTemplatePath(), leftKept, sparedKept);
        }
    }

    /** The shell round a box kept from {@code min} to {@code max}: its fluid cells now, outside the box. */
    private static Shell shell(net.minecraft.server.world.ServerWorld world, BlockPos min, BlockPos max) {
        var smin = new BlockPos(min.getX() - SHELL, world.getBottomY(), min.getZ() - SHELL);
        var smax = new BlockPos(max.getX() + SHELL, max.getY(), max.getZ() + SHELL);
        var fluid = new java.util.HashSet<Long>();
        var chunks = new java.util.HashSet<Long>();
        fluidIn(world, smin, smax, min, max, null, chunks, fluid::add);
        return new Shell(smin, smax, fluid, chunks);
    }

    /**
     * Every cell from {@code smin} to {@code smax}, but those from {@code min} to {@code max}, holding a fluid, given to
     * {@code out}; in loaded chunks only (those in {@code known}, when given; each loaded one added to {@code loaded},
     * when given). A section with no fluid in its palette is passed over without reading its cells.
     */
    private static void fluidIn(net.minecraft.server.world.ServerWorld world, BlockPos smin, BlockPos smax, BlockPos min, BlockPos max,
                                java.util.Set<Long> known, java.util.Set<Long> loaded, java.util.function.LongConsumer out) {
        var manager = world.getChunkManager();
        for (int cx = smin.getX() >> 4; cx <= smax.getX() >> 4; cx++) for (int cz = smin.getZ() >> 4; cz <= smax.getZ() >> 4; cz++) {
            long key = net.minecraft.util.math.ChunkPos.toLong(cx, cz);
            if (known != null && !known.contains(key)) continue;
            if (!manager.isChunkLoaded(cx, cz)) continue;
            var chunk = manager.getWorldChunk(cx, cz);
            if (chunk == null) continue;
            if (loaded != null) loaded.add(key);
            for (int sy = smin.getY() >> 4; sy <= smax.getY() >> 4; sy++) {
                int index = world.sectionCoordToIndex(sy);
                if (index < 0 || index >= chunk.getSectionArray().length) continue;
                var section = chunk.getSection(index);
                if (section.isEmpty() || !section.hasAny(state -> !state.getFluidState().isEmpty())) continue;
                int x0 = Math.max(smin.getX(), cx << 4), x1 = Math.min(smax.getX(), (cx << 4) + 15);
                int z0 = Math.max(smin.getZ(), cz << 4), z1 = Math.min(smax.getZ(), (cz << 4) + 15);
                int y0 = Math.max(smin.getY(), sy << 4), y1 = Math.min(smax.getY(), (sy << 4) + 15);
                for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                    if (x >= min.getX() && x <= max.getX() && y >= min.getY() && y <= max.getY() && z >= min.getZ() && z <= max.getZ()) continue;
                    if (section.getFluidState(x & 15, y & 15, z & 15).isEmpty()) continue;
                    out.accept(BlockPos.asLong(x, y, z));
                }
            }
        }
    }

    /**
     * The fluid in {@code kept}'s shell that was not there when it was kept, cleared without updates, but in another
     * test's plot, in ground a live test keeps, or in another box this test keeps (put back already). Logged, so a
     * fixture that lets its fluid out is named in every run it does; its test's pass or failure is not touched.
     */
    private static void clearSpilled(TestContext context, net.minecraft.test.GameTestState self, java.util.List<Kept> boxes, Kept kept,
                                     java.util.List<Box> others, java.util.List<Box> othersKept) {
        var shell = kept.shell();
        if (shell == null) return;
        var world = kept.world();
        long start = System.nanoTime();
        var spilled = new java.util.ArrayList<Long>();
        fluidIn(world, shell.min(), shell.max(), kept.min(), kept.max(), shell.chunks(), null, pos -> {
            if (!shell.fluid().contains(pos)) spilled.add(pos);
        });
        int cleared = 0;
        BlockPos first = null;
        var at = new BlockPos.Mutable();
        for (long pos : spilled) {
            at.set(pos);
            double x = at.getX() + 0.5, y = at.getY() + 0.5, z = at.getZ() + 0.5;
            if (within(others, x, y, z) || within(othersKept, x, y, z)) continue;
            boolean ours = false;
            for (var box : boxes) {
                if (box != kept && box.world() == world && at.getX() >= box.min().getX() && at.getX() <= box.max().getX()
                        && at.getY() >= box.min().getY() && at.getY() <= box.max().getY() && at.getZ() >= box.min().getZ() && at.getZ() <= box.max().getZ()) {
                    ours = true;
                    break;
                }
            }
            if (ours) continue;
            var state = world.getBlockState(at);
            BlockState dry = state.contains(net.minecraft.state.property.Properties.WATERLOGGED)
                    ? state.with(net.minecraft.state.property.Properties.WATERLOGGED, false)
                    : net.minecraft.block.Blocks.AIR.getDefaultState();
            world.setBlockState(at, dry, Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
            if (first == null) first = at.toImmutable();
            cleared++;
        }
        if (cleared > 0) {
            KindredMod.LOGGER.info("{}: {} fluid cells outside its kept ground cleared, the first at {} (its box {} to {}; {} ms)",
                    self == null ? "A fixture" : self.getTemplatePath(), cleared, first.toShortString(), kept.min().toShortString(),
                    kept.max().toShortString(), (System.nanoTime() - start) / 1_000_000);
        }
    }

    private static boolean within(java.util.List<Box> plots, double x, double y, double z) {
        for (var plot : plots) if (plot.contains(x, y, z)) return true;
        return false;
    }

    private static net.minecraft.test.GameTestState stateOf(TestContext context) {
        try {
            var field = TestContext.class.getDeclaredField("test");
            field.setAccessible(true);
            return (net.minecraft.test.GameTestState) field.get(context);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }
}
