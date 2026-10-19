package dev.kindred.core;

import java.util.Objects;

public final class Authorization {
    private Authorization() { }

    public static boolean mayControl(Actor actor, Companion companion) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(companion, "companion");
        return actor.operator() || companion.ownerId().equals(actor.playerId());
    }

    public static void requireControl(Actor actor, Companion companion) {
        if (!mayControl(actor, companion)) {
            throw new SecurityException("Only the owner or an operator may control this companion");
        }
    }
}
