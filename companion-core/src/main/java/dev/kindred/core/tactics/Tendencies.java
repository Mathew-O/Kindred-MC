package dev.kindred.core.tactics;

import java.util.EnumMap;
import java.util.Map;

/**
 * An opponent's tendencies (E-41c, Milestone 2), as a player learns them over a fight: how often it did a thing when
 * it had the chance, counted only over its chances (a block counts against the swings it could have blocked, not
 * against the whole fight), each count decaying with age so that it follows an opponent who changes, and with a
 * confidence from how much has been seen, so that a guess from two chances weighs little. And a short prediction of
 * where it will be, from where it has been moving, bounded in how far ahead it looks. Pure; a fight keeps one per
 * opponent, and the melee consults it only when it is switched on (its control is the melee without it).
 */
public final class Tendencies {
    /** What an opponent may do when it has the chance. */
    public enum Habit {
        /** Raise its shield when the player's swing is full and it is in reach. */
        BLOCKS,
        /** Swing as soon as it can, whatever its charge, rather than wait for a full swing. */
        SPAMS,
        /** Back off when it is hurt. */
        RETREATS_HURT,
        /** Strafe round the player while in reach. */
        STRAFES
    }

    /** A count's half-life, in chances: what was seen this many chances ago weighs half as much. */
    public static final double HALF_LIFE = 12;
    /** Chances (weighted) at which a tendency's confidence is a half. */
    public static final double CONFIDENT_AT = 6;
    /** The furthest ahead a position is predicted, in ticks. */
    public static final int HORIZON = 10;

    private static final double DECAY = Math.pow(0.5, 1.0 / HALF_LIFE);

    private final Map<Habit, double[]> counts = new EnumMap<>(Habit.class);

    /** A chance for {@code habit}, and whether the opponent took it. */
    public void chance(Habit habit, boolean took) {
        var c = counts.computeIfAbsent(habit, h -> new double[2]);
        c[0] = c[0] * DECAY + (took ? 1 : 0);
        c[1] = c[1] * DECAY + 1;
    }

    /** How often it takes the chance, weighted by recency, from a prior of a half; a half before any chance is seen. */
    public double rate(Habit habit) {
        var c = counts.get(habit);
        return c == null ? 0.5 : (c[0] + 1) / (c[1] + 2);
    }

    /** How sure the rate is, 0 to 1: the weighted chances seen against {@link #CONFIDENT_AT}. */
    public double confidence(Habit habit) {
        var c = counts.get(habit);
        double n = c == null ? 0 : c[1];
        return n / (n + CONFIDENT_AT);
    }

    /**
     * How likely it is to take the chance, the rate drawn toward a half by how unsure it is: what the melee uses, so
     * that a guess from few chances moves it little.
     */
    public double likely(Habit habit) {
        double k = confidence(habit);
        return k * rate(habit) + (1 - k) * 0.5;
    }

    /**
     * Where it will be {@code ticks} ahead, from where it is and how far it moved each tick of late (from what was seen
     * of it, not a velocity the server may not know for a client's player), held to that pace as a player holding a key
     * keeps it, and looking at most {@link #HORIZON} ticks ahead: further than that it will have turned.
     */
    public static double[] predict(double x, double z, double vx, double vz, int ticks) {
        int t = Math.max(0, Math.min(HORIZON, ticks));
        return new double[]{x + vx * t, z + vz * t};
    }
}
