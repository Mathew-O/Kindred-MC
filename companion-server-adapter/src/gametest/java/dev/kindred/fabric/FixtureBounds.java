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
    private static final ThreadLocal<int[]> PLACING = ThreadLocal.withInitial(() -> new int[1]);

    private static final class Outside {
        final Box plot;
        /** What it set outside, by block, and how many of those stood above the plot's floor, air apart. */
        final Map<String, Integer> kinds = new TreeMap<>();
        int above;
        int count, minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Outside(Box plot) { this.plot = plot; }
    }

    private static final Map<GameTestState, Outside> OUTSIDE = Collections.synchronizedMap(new IdentityHashMap<>());

    private FixtureBounds() { }

    public static GameTestState enter(GameTestState test) {
        var outer = CURRENT.get();
        CURRENT.set(test);
        return outer;
    }

    public static void exit(GameTestState test, GameTestState outer) {
        CURRENT.set(outer);
        if (!test.isCompleted()) return;
        var outside = OUTSIDE.remove(test);
        if (outside == null) return;
        var plot = outside.plot;
        String kinds = outside.kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
        KindredMod.LOGGER.warn("Fixture ground outside its plot: {} set {} blocks ({} not air above its floor; {}) from {}, {}, {} to {}, {}, {}; its plot {}, {}, {} to {}, {}, {}",
                test.getTemplatePath(), outside.count, outside.above, kinds, outside.minX, outside.minY, outside.minZ, outside.maxX, outside.maxY, outside.maxZ,
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

    public static void wrote(BlockPos pos, BlockState state) {
        var test = CURRENT.get();
        if (test == null || PLACING.get()[0] > 0) return;
        Box plot;
        try {
            plot = test.getBoundingBox();
        } catch (RuntimeException unplaced) {
            return;
        }
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (x >= plot.minX && x < plot.maxX && y >= plot.minY && y < plot.maxY && z >= plot.minZ && z < plot.maxZ) return;
        var outside = OUTSIDE.computeIfAbsent(test, t -> new Outside(plot));
        outside.count++;
        outside.kinds.merge(Registries.BLOCK.getId(state.getBlock()).getPath(), 1, Integer::sum);
        if (!state.isAir() && y > plot.minY) outside.above++;
        outside.minX = Math.min(outside.minX, x); outside.minY = Math.min(outside.minY, y); outside.minZ = Math.min(outside.minZ, z);
        outside.maxX = Math.max(outside.maxX, x); outside.maxY = Math.max(outside.maxY, y); outside.maxZ = Math.max(outside.maxZ, z);
    }
}
