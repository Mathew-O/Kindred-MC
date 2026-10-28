package dev.kindred.core.progression;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * In plain terms: one step of a survival playthrough, like "craft a stone pickaxe" or "enter the Nether":
 * what must come before it, and how to tell from the player's real items and surroundings that it has been
 * reached.
 *
 * Technical: Definition of a progression milestone in the milestone graph.
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

    /** Whether the player's real items and surroundings meet this step right now. */
    public boolean isSatisfied(ProgressionObservation obs) {
        return postcondition.test(obs);
    }
}
