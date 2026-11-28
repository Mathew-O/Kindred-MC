package dev.kindred.core.tactics;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/**
 * In plain terms: which way a player gets away from a threat when straight away is a wall or a drop. It takes straight
 * away if it can walk that way; if not, the way nearest to it that it can walk and that still takes it farther off; and
 * once it has picked a way, it keeps to it while it still can, rather than swing between two nearly as good.
 *
 * Technical: the walkable way-off heading (F-25c4dh5). The straight back-off and the creeper run aimed at a raw point
 * straight away from the threat, the ground there unseen; the partial walk was refused, or ended within two blocks, when no
 * step that way came nearer. Of {@link #HEADINGS} headings evenly round, numbered from +x towards +z, the caller says which
 * can be walked (the first few cells along it, by the route finder's own reading of the blocks). Kept: the last heading,
 * while walkable and gaining; else the straight one, while walkable; else the walkable gaining heading with the greatest
 * dot product with straight away. Gaining: a dot product with straight away above zero. None: empty. Pure.
 */
public final class WayOffHeading {
    /** Headings tried, evenly round. */
    public static final int HEADINGS = 16;

    private WayOffHeading() { }

    /** The unit vector of heading {@code i}: x, then z. */
    public static double[] unit(int i) {
        double angle = 2 * Math.PI * Math.floorMod(i, HEADINGS) / HEADINGS;
        return new double[] {Math.cos(angle), Math.sin(angle)};
    }

    /** The heading nearest the direction {@code (dx, dz)}. */
    public static int nearest(double dx, double dz) {
        return Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (2 * Math.PI) * HEADINGS), HEADINGS);
    }

    /** How far heading {@code i} goes the way of straight away {@code (ax, az)}, a unit vector: its dot product with it. */
    public static double gain(int i, double ax, double az) {
        double[] u = unit(i);
        return u[0] * ax + u[1] * az;
    }

    /**
     * The heading to take away along {@code (ax, az)} (any length but zero), with {@code walkable} saying which headings can
     * be walked and {@code previous} the heading last taken on this way off, or -1.
     */
    public static OptionalInt choose(double ax, double az, IntPredicate walkable, int previous) {
        double length = Math.hypot(ax, az);
        if (length < 1.0E-6) return OptionalInt.empty();
        ax /= length;
        az /= length;
        if (previous >= 0 && gain(previous, ax, az) > 0 && walkable.test(previous)) return OptionalInt.of(previous);
        int straight = nearest(ax, az);
        if (walkable.test(straight)) return OptionalInt.of(straight);
        int best = -1;
        double bestGain = 0;
        for (int i = 0; i < HEADINGS; i++) {
            double g = gain(i, ax, az);
            if (g > bestGain + 1.0E-9 && walkable.test(i)) {
                bestGain = g;
                best = i;
            }
        }
        return best < 0 ? OptionalInt.empty() : OptionalInt.of(best);
    }
}
