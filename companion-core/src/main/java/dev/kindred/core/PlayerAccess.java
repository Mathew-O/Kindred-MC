package dev.kindred.core;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Administrative control grants, separate from an artificial player's identity, goals or team. */
public record PlayerAccess(UUID playerId, Set<UUID> controllers) {
    public PlayerAccess {
        Objects.requireNonNull(playerId);
        controllers = Set.copyOf(controllers);
    }

    public boolean mayControl(Actor actor) {
        Objects.requireNonNull(actor);
        return actor.operator() || (actor.playerId() != null && controllers.contains(actor.playerId()));
    }
}
