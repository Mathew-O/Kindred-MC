package dev.kindred.core.expedition;

import dev.kindred.core.TaskPreferences.Priority;

/**
 * In plain terms: the next step for an exploring player: which way to walk, how far, and how urgent it is.
 *
 * Technical: Recommended traversal action and urgency derived from expedition state.
 */
public record ExpeditionPlan(
        String action, // "native_traverse" or "none"
        String direction, // "north", "south", "east", "west"
        int blocks, // 1..3
        Priority priority,
        double need,
        ReturnReason returnReason
) {
    /** No step to take: stay put. */
    public static ExpeditionPlan idle() {
        return new ExpeditionPlan("none", "north", 0, Priority.ROUTINE, 0.0, ReturnReason.NONE);
    }
}
