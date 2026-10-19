package dev.kindred.core;

public enum LifecycleState {
    SLEEPING, WAITING, WAKING, ACTIVE, RETURNING, PAUSED, BLOCKED, FAULTED, REMOVED;

    public boolean chargesAwakeBudget() {
        return this == ACTIVE || this == RETURNING;
    }
}
