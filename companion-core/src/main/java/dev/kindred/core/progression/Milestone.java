package dev.kindred.core.progression;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Definition of a progression milestone in the milestone graph.
 */
public record Milestone(
        MilestoneId id,
        String displayName,
        Set<MilestoneId> prerequisites,
        Predicate<ProgressionObservation> postcondition,
        double priorityWeight
) {
    public Milestone {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        prerequisites = Set.copyOf(Objects.requireNonNull(prerequisites, "prerequisites"));
        Objects.requireNonNull(postcondition, "postcondition");
        if (priorityWeight < 0.0) throw new IllegalArgumentException("Priority weight cannot be negative: " + priorityWeight);
    }

    public boolean isSatisfied(ProgressionObservation obs) {
        return postcondition.test(obs);
    }
}
