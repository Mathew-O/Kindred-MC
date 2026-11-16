package dev.kindred.fabric;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTestState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The blocks a test sets outside its own plot while it ticks, its start and steps among them, said once as the test
 * ends (F-22c75b). The test server spaces tests by their structures and keeps what each one changed, so ground a
 * fixture lays or puts back past its structure lies in or beside a later test's plot: the kiosk's built copy stood 7
 * blocks from the pit test's player, which walked to it for a log (F-22c75a). It only reports; it changes nothing.
 */
public final class FixtureBounds {
    private static final ThreadLocal<GameTestState> CURRENT = new ThreadLocal<>();
    /** The player whose own placing or breaking is under way, on the server's tick, outside any test's step. */
    private static final ThreadLocal<net.minecraft.server.network.ServerPlayerEntity> ACTING = new ThreadLocal<>();
    /** Each test's players, by the test whose step brought them into the world. */
    private static final Map<java.util.UUID, GameTestState> OWNER = new java.util.concurrent.ConcurrentHashMap<>();
    /** What a test's players set outside its plot, apart from what its fixture set. */
    private static final Map<GameTestState, Outside> BY_PLAYERS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ThreadLocal<int[]> PLACING = ThreadLocal.withInitial(() -> new int[1]);

    /** The boxes each test keeps, put back as it ends: what it sets there is not left behind. */
    private static final Map<GameTestState, java.util.List<Box>> KEPT = Collections.synchronizedMap(new IdentityHashMap<>());

    private static final class Outside {
        final Box plot;
        /** Of those set outside its plot, how many lie in ground it keeps and puts back as it ends. */
        int kept;
        /** What it set outside, by block, and how many of those stood above the plot's floor, air apart. */
        final Map<String, Integer> kinds = new TreeMap<>();
        int above;
        int count, minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Outside(Box plot) { this.plot = plot; }
    }

    /** The tests placed and not yet ended: their plots are theirs, whatever a neighbour kept there. */
    private static final java.util.Set<GameTestState> LIVE = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private static final Map<GameTestState, Outside> OUTSIDE = Collections.synchronizedMap(new IdentityHashMap<>());

    private FixtureBounds() { }

    public static GameTestState enter(GameTestState test) {
        var outer = CURRENT.get();
        CURRENT.set(test);
        return outer;
    }

    /** {@code test} placed by the server: from now until it ends its plot is its own. */
    /**
     * Each player that comes into the world is the test's whose plot it comes into, or the nearest placed test's within
     * sixteen blocks: a test's players are made in its plot, their bodies brought up after the step that asks for them.
     * One already known keeps its test (a body made again after a death or a restart).
     */
    public static void install() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof net.minecraft.server.network.ServerPlayerEntity) || OWNER.containsKey(entity.getUuid())) return;
            var test = CURRENT.get();
            if (test == null) test = plotOf(entity.getX(), entity.getY(), entity.getZ());
            if (test != null) OWNER.put(entity.getUuid(), test);
        });
    }

    private static GameTestState plotOf(double x, double y, double z) {
        GameTestState nearest = null;
        double best = 16 * 16;
        synchronized (LIVE) {
            for (var test : LIVE) {
                if (test.isCompleted()) continue;
                Box plot;
                try {
                    plot = test.getBoundingBox();
                } catch (RuntimeException unplaced) {
                    continue;
                }
                if (plot.expand(1).contains(x, y, z)) return test;
                double dx = Math.max(0, Math.max(plot.minX - x, x - plot.maxX)), dz = Math.max(0, Math.max(plot.minZ - z, z - plot.maxZ));
                double d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                    nearest = test;
                }
            }
        }
        return nearest;
    }

    /** {@code player}'s own placing or breaking begins ({@code null}: it ends); the one before it, to put back after. */
    public static net.minecraft.server.network.ServerPlayerEntity acting(net.minecraft.server.network.ServerPlayerEntity player) {
        var outer = ACTING.get();
        ACTING.set(player);
        return outer;
    }

    /** {@code test} keeps the box from {@code min} to {@code max}, to put back as it ends. */
    static void kept(GameTestState test, BlockPos min, BlockPos max) {
        if (test == null) return;
        KEPT.computeIfAbsent(test, t -> Collections.synchronizedList(new java.util.ArrayList<>()))
                .add(new Box(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1));
    }

    /** The live test whose plot or kept ground holds the point, by its template path; null for none (F-22c84). */
    static String liveTestAt(double x, double y, double z) {
        synchronized (LIVE) {
            for (var test : LIVE) {
                if (test.isCompleted()) continue;
                try {
                    if (test.getBoundingBox().expand(1).contains(x, y, z)) return test.getTemplatePath();
                } catch (RuntimeException unplaced) {
                    continue;
                }
                var kept = KEPT.get(test);
                if (kept != null) synchronized (kept) {
                    for (var box : kept) if (box.contains(x, y, z)) return test.getTemplatePath();
                }
            }
        }
        return null;
    }

    /** The player whose own step is running now, by name; null for none (F-22c84). */
    static String actingName() {
        var player = ACTING.get();
        return player == null ? null : player.getGameProfile().getName();
    }

    public static GameTestState placed(GameTestState test) {
        if (test != null) LIVE.add(test);
        return test;
    }

    /**
     * The plots of the tests placed and not yet ended other than {@code self}'s, each with the barriers and structure
     * block the server sets round it: what a fixture putting its ground back must leave as it is. Empty where no
     * test was seen placed (the failure corpus runs without this audit).
     */
    static java.util.List<Box> othersPlots(GameTestState self) {
        java.util.List<Box> plots = new java.util.ArrayList<>();
        synchronized (LIVE) {
            for (var test : LIVE) {
                if (test == self || test.isCompleted()) continue;
                try {
                    plots.add(test.getBoundingBox().expand(1));
                } catch (RuntimeException unplaced) {
                    // Not yet in the world: nothing of its own to leave.
                }
            }
        }
        return plots;
    }

    /**
     * The ground the tests placed and not yet ended, other than {@code self}, keep: as much theirs as their plots. A live
     * test's field reaches past its plot, and a restore of a neighbour's box over it took the lectern and the cart's item
     * from a bring still running, once the kept chunks were held loaded (F-22c75b; 3 bring tests and 7 in all in one full
     * suite, #331's run).
     */
    static java.util.List<Box> othersKept(GameTestState self) {
        java.util.List<Box> boxes = new java.util.ArrayList<>();
        synchronized (LIVE) {
            for (var test : LIVE) {
                if (test == self || test.isCompleted()) continue;
                var kept = KEPT.get(test);
                if (kept != null) synchronized (kept) { boxes.addAll(kept); }
            }
        }
        return boxes;
    }

    public static void exit(GameTestState test, GameTestState outer) {
        CURRENT.set(outer);
        if (!test.isCompleted()) return;
        LIVE.remove(test);
        placement(test);
        var byPlayers = BY_PLAYERS.remove(test);
        if (byPlayers != null) report(test, byPlayers, "its players");
        KEPT.remove(test);
        FLUIDS_NOTED.remove(test);
        var outside = OUTSIDE.remove(test);
        if (outside == null) return;
        report(test, outside, "it");
    }

    /**
     * Where the test stood and what ground it kept, as it ends: with the warnings, which tests' leftovers lay in another's
     * plot or kept ground, the same run's layout read off the log (F-22c75b).
     */
    private static void placement(GameTestState test) {
        String plot;
        try {
            var box = test.getBoundingBox();
            plot = (int) box.minX + ", " + (int) box.minY + ", " + (int) box.minZ + " to " + ((int) box.maxX - 1) + ", " + ((int) box.maxY - 1) + ", " + ((int) box.maxZ - 1);
        } catch (RuntimeException unplaced) {
            return;
        }
        var boxes = KEPT.get(test);
        String kept = "none";
        if (boxes != null) {
            synchronized (boxes) {
                kept = boxes.stream().map(b -> (int) b.minX + ", " + (int) b.minY + ", " + (int) b.minZ + " to " + ((int) b.maxX - 1) + ", "
                        + ((int) b.maxY - 1) + ", " + ((int) b.maxZ - 1)).collect(Collectors.joining("; "));
            }
        }
        KindredMod.LOGGER.info("Fixture placement: {}: plot {}; kept {}", test.getTemplatePath(), plot, kept);
    }

    private static void report(GameTestState test, Outside outside, String who) {
        var plot = outside.plot;
        if (outside.count == outside.kept) {
            KindredMod.LOGGER.info("Fixture ground outside its plot: {}: {} set {} blocks, all in ground it keeps", test.getTemplatePath(), who, outside.count);
            return;
        }
        String kinds = outside.kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
        KindredMod.LOGGER.warn("Fixture ground outside its plot: {}: {} set {} blocks, {} in ground it keeps; left: {} not air above its floor; {}; from {}, {}, {} to {}, {}, {}; its plot {}, {}, {} to {}, {}, {}",
                test.getTemplatePath(), who, outside.count, outside.kept, outside.above, kinds,
                outside.minX, outside.minY, outside.minZ, outside.maxX, outside.maxY, outside.maxZ,
                (int) plot.minX, (int) plot.minY, (int) plot.minZ, (int) plot.maxX - 1, (int) plot.maxY - 1, (int) plot.maxZ - 1);
    }

    /**
     * While the test server places a test (its structure, the area cleared round it, its barriers) or marks one (its
     * beacon, glass and lectern): its own work, not the fixture's. The next batch is placed within the tick of the
     * test that ends the one before.
     */
    public static void placing(boolean placing) {
        PLACING.get()[0] += placing ? 1 : -1;
    }

    /** How many fluid writes outside its kept ground each test has had named, up to {@link #FLUIDS_NAMED}. */
    private static final Map<GameTestState, int[]> FLUIDS_NOTED = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final int FLUIDS_NAMED = 3;

    /**
     * A fluid set above its floor, outside the ground a test keeps, by a test that keeps some: the first three a test,
     * each named with the frames that set it (F-22c75b, log only). The build battle's kept field had water left just
     * over its top in CI on #380 and #385, a sheet a block wide set in its own steps, from no write of its fixture's.
     */
    private static void fluidOutside(GameTestState test, BlockPos pos, BlockState state, boolean byPlayers) {
        int[] noted = FLUIDS_NOTED.computeIfAbsent(test, t -> new int[1]);
        if (noted[0]++ >= FLUIDS_NAMED) return;
        String from = StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().endsWith("FixtureBounds") && !f.getMethodName().contains("kindred$"))
                .limit(14)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .collect(Collectors.joining(" < ")));
        KindredMod.LOGGER.info("Fluid set outside kept ground: {}: {} at {} by {}{}; from {}", test.getTemplatePath(), state, pos.toShortString(),
                byPlayers ? "its players" : "its own step", noted[0] == FLUIDS_NAMED ? " (the last named for this test)" : "", from);
    }

    public static void wrote(BlockPos pos, BlockState state) {
        if (PLACING.get()[0] > 0) return;
        var test = CURRENT.get();
        var into = OUTSIDE;
        if (test == null) {
            var player = ACTING.get();
            if (player == null) return;
            test = OWNER.get(player.getUuid());
            if (test == null || test.isCompleted()) return;
            into = BY_PLAYERS;
        }
        Box plot;
        try {
            plot = test.getBoundingBox();
        } catch (RuntimeException unplaced) {
            return;
        }
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (x >= plot.minX && x < plot.maxX && y >= plot.minY && y < plot.maxY && z >= plot.minZ && z < plot.maxZ) return;
        var outside = into.computeIfAbsent(test, t -> new Outside(plot));
        outside.count++;
        var boxes = KEPT.get(test);
        if (boxes != null) {
            synchronized (boxes) {
                for (var box : boxes) {
                    if (box.contains(x + 0.5, y + 0.5, z + 0.5)) {
                        outside.kept++;
                        return;
                    }
                }
            }
        }
        if (boxes != null && !state.getFluidState().isEmpty() && y > plot.minY) fluidOutside(test, pos, state, into == BY_PLAYERS);
        outside.kinds.merge(Registries.BLOCK.getId(state.getBlock()).getPath(), 1, Integer::sum);
        if (!state.isAir() && y > plot.minY) outside.above++;
        outside.minX = Math.min(outside.minX, x); outside.minY = Math.min(outside.minY, y); outside.minZ = Math.min(outside.minZ, z);
        outside.maxX = Math.max(outside.maxX, x); outside.maxY = Math.max(outside.maxY, y); outside.maxZ = Math.max(outside.maxZ, z);
    }
}
