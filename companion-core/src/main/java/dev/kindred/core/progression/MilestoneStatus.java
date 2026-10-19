package dev.kindred.core.progression;

/**
 * Status of a milestone within the progression graph.
 */
public enum MilestoneStatus {
    /**
     * Prerequisites are not yet satisfied.
     */
    LOCKED,

    /**
     * All prerequisites are satisfied, but the milestone's physical postconditions are not yet met.
     * Part of the active progression frontier.
     */
    UNLOCKED,

    /**
     * Verified physical postconditions are satisfied.
     */
    ACHIEVED
}
