package dev.kindred.core.progression;

import java.util.Objects;

/**
 * In plain terms: how one step stands right now: whether it is met by what the player has, and whether it was
 * ever reached before. A step reached before but lost (say the pickaxe broke) needs recovering, without
 * forgetting it was done.
 *
 * Technical: Snapshot of a milestone's current evaluation.
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
