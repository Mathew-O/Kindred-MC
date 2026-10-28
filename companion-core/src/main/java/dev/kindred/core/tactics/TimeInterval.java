package dev.kindred.core.tactics;

import java.util.Objects;

/**
 * In plain terms: a range of time in game ticks (20 a second), from the shortest to the longest something
 * could take.
 *
 * Technical: Immutable bounded interval representing a duration range in Minecraft game ticks (1/20 sec).
 * Enforces non-negative bounds where minTicks <= maxTicks.
 */
public record TimeInterval(int minTicks, int maxTicks) {

    public TimeInterval {
        if (minTicks < 0) {
            throw new IllegalArgumentException("minTicks must be non-negative: " + minTicks);
        }
        if (maxTicks < minTicks) {
            throw new IllegalArgumentException("maxTicks must be >= minTicks (" + minTicks + "): " + maxTicks);
        }
    }

    /** No time at all. */
    public static TimeInterval zero() {
        return new TimeInterval(0, 0);
    }

    /** One exact length of time. */
    public static TimeInterval of(int exact) {
        return new TimeInterval(exact, exact);
    }

    /** Two time ranges added together. */
    public TimeInterval add(TimeInterval other) {
        Objects.requireNonNull(other, "other");
        return new TimeInterval(this.minTicks + other.minTicks, this.maxTicks + other.maxTicks);
    }

    /** Whether it is sure to finish before a deadline. */
    public boolean completesBefore(int deadlineTicks) {
        return maxTicks <= deadlineTicks;
    }
}
