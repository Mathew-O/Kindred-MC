package dev.kindred.core.progression;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of evaluating a player's physical progression observation against the milestone graph.
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

    public boolean isAchieved(MilestoneId id) {
        MilestoneSnapshot snapshot = snapshots.get(id);
        return snapshot != null && snapshot.status() == MilestoneStatus.ACHIEVED;
    }

    public boolean isPhysicalConditionMet(MilestoneId id) {
        MilestoneSnapshot snapshot = snapshots.get(id);
        return snapshot != null && snapshot.physicalPostconditionSatisfied();
    }

    public Optional<MilestoneSnapshot> snapshot(MilestoneId id) {
        return Optional.ofNullable(snapshots.get(id));
    }
}
