package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: who is giving a command: a particular player, or the server console. It comes from the
 * server itself, never from an AI model, and says whether the giver is an operator.
 *
 * Technical: Trusted, authenticated command context supplied by the server adapter, never by a model.
 */
public record Actor(UUID playerId, boolean operator) {
    public Actor {
        if (!operator) Objects.requireNonNull(playerId, "playerId");
    }

    /** A command from a normal (non-operator) player. */
    public static Actor player(UUID playerId) {
        return new Actor(playerId, false);
    }

    /** A command from the server console, which counts as an operator. */
    public static Actor console() {
        return new Actor(null, true);
    }
}
