package dev.kindred.core;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: who may give an AI player commands: its controllers and server operators. This is separate
 * from who the player is and which team it is on.
 *
 * Technical: Administrative control grants, separate from an artificial player's identity, goals or team.
 */
public record PlayerAccess(UUID playerId, Set<UUID> controllers) {
    public PlayerAccess {
        Objects.requireNonNull(playerId);
        controllers = Set.copyOf(controllers);
    }

    /** Whether this person may control the player. */
    public boolean mayControl(Actor actor) {
        Objects.requireNonNull(actor);
        return actor.operator() || (actor.playerId() != null && controllers.contains(actor.playerId()));
    }
}
