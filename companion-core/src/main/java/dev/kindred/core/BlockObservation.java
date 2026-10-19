package dev.kindred.core;

import java.util.Objects;

/**
 * One observed block of interest: a timestamped fact, never a live world reference. Only exposed blocks (at
 * least one neighbour that is air or non-opaque) are ever positioned; buried matches are counted but hidden,
 * so native access cannot become X-ray perception.
 */
public record BlockObservation(WorkSetup.BlockPoint position, String blockId, long observedAtMillis) {
    public BlockObservation {
        Objects.requireNonNull(position, "position");
        if (blockId == null || blockId.isBlank()) throw new IllegalArgumentException("Observation needs a block identity");
    }

    public SectionKey section() { return SectionKey.of(position); }

    public long squaredDistanceTo(WorkSetup.BlockPoint from) {
        long dx = position.x() - from.x();
        long dy = position.y() - from.y();
        long dz = position.z() - from.z();
        return dx * dx + dy * dy + dz * dz;
    }

    public long ageMillis(long now) { return Math.max(0, now - observedAtMillis); }
}
