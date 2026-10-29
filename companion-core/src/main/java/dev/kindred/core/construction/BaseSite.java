package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: where a player puts a base: the nearest flat, open ground to a chosen spot that is big
 * enough, with room round it to walk and stand on, kept apart from its other bases. It judges only ground it
 * has seen.
 *
 * Technical: Where a player puts a base (M-01c): the nearest flat, open ground to its anchor big enough for
 * the stage, with a ring of ground round it to walk and scaffold on, clear of its other bases. Ground is read
 * from what the player could see standing there: the top of each column, whether that top is ground to build
 * on (not water, lava, a tree, a container or another's build), and how many open cells are over it. Pure.
 */
public final class BaseSite {
    /**
     * One column of ground: its top block's height, whether that block is ground to build on, the open cells over it,
     * and whether that top is water (a rail line is laid over it on a bridge, E-45e).
     */
    public record Column(int top, boolean firm, int open, boolean water) {
        /** A column of ground that is not water. */
        public Column(int top, boolean firm, int open) {
            this(top, firm, open, false);
        }
    }

    /** The ground the player knows, column by column; empty where it does not know. */
    @FunctionalInterface
    public interface Ground {
        Optional<Column> at(int x, int z);
    }

    /** A footprint on the ground, both corners included. */
    public record Footprint(int minX, int minZ, int maxX, int maxZ) {
        public Footprint {
            if (maxX < minX || maxZ < minZ) throw new IllegalArgumentException("An empty footprint");
        }

        /** Whether this footprint shares any ground with the given area. */
        boolean overlaps(int x0, int z0, int x1, int z1) {
            return x0 <= maxX && x1 >= minX && z0 <= maxZ && z1 >= minZ;
        }
    }

    /** Ground round a site for walking and scaffolding. */
    public static final int RING = 1;
    /** Room kept between a site and the player's other bases. */
    public static final int APART = 3;

    private BaseSite() { }

    /**
     * The origin (north-west corner of the floor, laid on top of the ground) of the nearest site of {@code width} by
     * {@code depth} with {@code height} open over it, its centre within {@code radius} of the anchor, or empty. The
     * ground under it and its ring may step down one block from the highest column, no more: the floor bridges a dip of
     * one, as a player levels a site by eye.
     */
    public static Optional<Pos> choose(Ground ground, int anchorX, int anchorZ, int radius, int width, int depth, int height,
                                       List<Footprint> avoid) {
        Objects.requireNonNull(ground);
        Objects.requireNonNull(avoid);
        if (width < 1 || depth < 1 || height < 1 || radius < 0) throw new IllegalArgumentException("Not a site");
        var origins = new ArrayList<int[]>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz > radius * radius) continue;
            origins.add(new int[] {anchorX + dx - width / 2, anchorZ + dz - depth / 2, dx * dx + dz * dz});
        }
        // Nearest first; ties north-west first, so the choice never depends on iteration order.
        origins.sort(Comparator.<int[]>comparingInt(o -> o[2]).thenComparingInt(o -> o[1]).thenComparingInt(o -> o[0]));
        for (int[] o : origins) {
            int x0 = o[0] - RING, z0 = o[1] - RING, x1 = o[0] + width - 1 + RING, z1 = o[1] + depth - 1 + RING;
            boolean clash = false;
            for (var other : avoid) {
                if (other.overlaps(x0 - APART, z0 - APART, x1 + APART, z1 + APART)) {
                    clash = true;
                    break;
                }
            }
            if (clash) continue;
            var floor = level(ground, o[0], o[1], width, depth, height);
            if (floor.isPresent()) return Optional.of(new Pos(o[0], floor.get(), o[1]));
        }
        return Optional.empty();
    }

    /** The floor's height for a site at x, z, if its ground is firm, level within one, and open enough over it. */
    private static Optional<Integer> level(Ground ground, int x, int z, int width, int depth, int height) {
        int high = Integer.MIN_VALUE, low = Integer.MAX_VALUE;
        for (int cx = x - RING; cx < x + width + RING; cx++) for (int cz = z - RING; cz < z + depth + RING; cz++) {
            var column = ground.at(cx, cz);
            if (column.isEmpty() || !column.get().firm()) return Optional.empty();
            high = Math.max(high, column.get().top());
            low = Math.min(low, column.get().top());
            if (high - low > 1) return Optional.empty();
        }
        // The floor goes on top of the highest ground; the build needs its height clear over the floor, and the ring
        // room to stand (two cells) over its own ground.
        int floor = high + 1;
        for (int cx = x - RING; cx < x + width + RING; cx++) for (int cz = z - RING; cz < z + depth + RING; cz++) {
            var column = ground.at(cx, cz).orElseThrow();
            boolean inside = cx >= x && cx < x + width && cz >= z && cz < z + depth;
            int needed = inside ? floor + height - column.top() : 2;
            if (column.open() < needed) return Optional.empty();
        }
        return Optional.of(floor);
    }
}
