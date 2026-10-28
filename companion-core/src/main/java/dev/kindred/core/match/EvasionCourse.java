package dev.kindred.core.match;

import java.util.ArrayList;
import java.util.List;

/**
 * Which way a chased player runs (M-02c, the owner's note of 2026-10-23). Straight away from its pursuer first, then
 * a little to either side, then square to it, then back past it at an angle; but never toward its own home: a player
 * chased in a world of others does not lead them to its base. Only when every way off is toward home does it run that
 * way all the same, as away matters more than where. Pure.
 */
public final class EvasionCourse {
    /** The turns from straight away it tries, in order, in degrees. */
    static final double[] TURNS = {0, 45, -45, 90, -90, 135, -135};
    /** A way off within this many degrees of the way home is toward home. */
    public static final double HOME_CONE = 60;

    private EvasionCourse() { }

    /**
     * The turns from straight away to try, in order. {@code awayX}, {@code awayZ}: straight away from the pursuer;
     * {@code homeX}, {@code homeZ}: toward its home, or both zero when it has none to keep from it.
     */
    public static List<Double> turns(double awayX, double awayZ, double homeX, double homeZ) {
        var all = new ArrayList<Double>();
        for (double t : TURNS) all.add(t);
        double home = Math.hypot(homeX, homeZ), away = Math.hypot(awayX, awayZ);
        if (home < 1e-6 || away < 1e-6) return all;
        var kept = new ArrayList<Double>();
        for (double turn : TURNS) {
            double r = Math.toRadians(turn), c = Math.cos(r), s = Math.sin(r);
            // Turned as the adapter turns its way off (Minecraft's rotateY): x' = x cos + z sin, z' = z cos - x sin.
            double x = awayX * c + awayZ * s, z = awayZ * c - awayX * s;
            double cos = (x * homeX + z * homeZ) / (away * home);
            if (cos < Math.cos(Math.toRadians(HOME_CONE))) kept.add(turn);
        }
        return kept.isEmpty() ? all : kept;
    }

    /** Whether a way off, turned {@code turn} degrees from straight away, leads toward home. */
    public static boolean towardHome(double awayX, double awayZ, double homeX, double homeZ, double turn) {
        return !turns(awayX, awayZ, homeX, homeZ).contains(turn) && Math.hypot(homeX, homeZ) > 1e-6;
    }
}
