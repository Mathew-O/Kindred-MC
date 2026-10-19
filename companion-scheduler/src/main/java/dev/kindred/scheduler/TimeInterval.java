package dev.kindred.scheduler;

import java.time.Instant;
import java.util.Objects;

public record TimeInterval(Instant startInclusive, Instant endExclusive) {
    public TimeInterval {
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("Interval must have a positive duration");
        }
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(startInclusive) && instant.isBefore(endExclusive);
    }
}
