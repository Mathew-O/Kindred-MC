package dev.kindred.core;

import java.util.Objects;

/**
 * In plain terms: the simple rule for who may control an older companion: its owner, or a server operator.
 */
public final class Authorization {
    private Authorization() { }

    /** Whether this person may control this companion. */
    public static boolean mayControl(Actor actor, Companion companion) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(companion, "companion");
        return actor.operator() || companion.ownerId().equals(actor.playerId());
    }

    /** Stops with an error unless this person may control this companion. */
    public static void requireControl(Actor actor, Companion companion) {
        if (!mayControl(actor, companion)) {
            throw new SecurityException("Only the owner or an operator may control this companion");
        }
    }
}
