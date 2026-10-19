package dev.kindred.core.progression;

import java.util.Objects;

/**
 * Snapshot of a milestone's current evaluation.
 * Tracks physical postcondition satisfaction vs historical achievement.
 * "Lost gear creates recovery work without erasing achievement history" (independent-progression.md).
 */
public record MilestoneSnapshot(
        MilestoneId id,
        MilestoneStatus status,
        boolean physicalPostconditionSatisfied,
        boolean historicallyAchieved,
        long firstAchievedTick
) {
    public MilestoneSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }

    /**
     * True if the milestone is historically achieved, but its physical postconditions
     * are currently lost (e.g. pickaxe broke, requiring recovery).
     */
    public boolean needsRecovery() {
        return historicallyAchieved && !physicalPostconditionSatisfied;
    }
}
