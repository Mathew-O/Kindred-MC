package dev.kindred.core;

/**
 * In plain terms: the states an AI player's saved record can be in: asleep, waiting, waking up, active,
 * coming back, paused, blocked, faulted or removed.
 */
public enum LifecycleState {
    SLEEPING, WAITING, WAKING, ACTIVE, RETURNING, PAUSED, BLOCKED, FAULTED, REMOVED;

    /**
     * Whether time in this state counts against the player's daily play time (only while active or on its way
     * back).
     */
    public boolean chargesAwakeBudget() {
        return this == ACTIVE || this == RETURNING;
    }
}
