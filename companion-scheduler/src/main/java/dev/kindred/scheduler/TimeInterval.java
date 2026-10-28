package dev.kindred.scheduler;

import java.time.Instant;
import java.util.Objects;

/**
 * In plain terms: a stretch of time with a start and an end.
 */
public record TimeInterval(Instant startInclusive, Instant endExclusive) {
    public TimeInterval {
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("Interval must have a positive duration");
        }
    }

    /** Whether a moment falls inside this stretch (the start counts, the end does not). */
    public boolean contains(Instant instant) {
        return !instant.isBefore(startInclusive) && instant.isBefore(endExclusive);
    }
}
