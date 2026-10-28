package dev.kindred.core.progression;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: the result of reading the progression map for a player: each step's status, the steps to
 * work on next, and the steps that need recovering.
 *
 * Technical: Result of evaluating a player's physical progression observation against the milestone graph.
 */
public record ProgressionState(
        long tick,
        Map<MilestoneId, MilestoneSnapshot> snapshots,
        List<MilestoneId> activeFrontier,
        List<MilestoneId> recoveryFrontier
) {
    public ProgressionState {
        snapshots = Map.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        activeFrontier = List.copyOf(Objects.requireNonNull(activeFrontier, "activeFrontier"));
        recoveryFrontier = List.copyOf(Objects.requireNonNull(recoveryFrontier, "recoveryFrontier"));
    }

    /** Whether a step was ever reached. */
    public boolean isAchieved(MilestoneId id) {
        MilestoneSnapshot snapshot = snapshots.get(id);
        return snapshot != null && snapshot.status() == MilestoneStatus.ACHIEVED;
    }

    /** Whether a step is met by what the player has right now. */
    public boolean isPhysicalConditionMet(MilestoneId id) {
        MilestoneSnapshot snapshot = snapshots.get(id);
        return snapshot != null && snapshot.physicalPostconditionSatisfied();
    }

    /** How one step stands. */
    public Optional<MilestoneSnapshot> snapshot(MilestoneId id) {
        return Optional.ofNullable(snapshots.get(id));
    }
}
