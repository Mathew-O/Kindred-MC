package dev.kindred.core.expedition;

import dev.kindred.core.TaskPreferences.Priority;

/**
 * Recommended traversal action and urgency derived from expedition state.
 */
public record ExpeditionPlan(
        String action, // "native_traverse" or "none"
        String direction, // "north", "south", "east", "west"
        int blocks, // 1..3
        Priority priority,
        double need,
        ReturnReason returnReason
) {
    public static ExpeditionPlan idle() {
        return new ExpeditionPlan("none", "north", 0, Priority.ROUTINE, 0.0, ReturnReason.NONE);
    }
}
