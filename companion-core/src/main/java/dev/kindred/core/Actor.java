package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/** Trusted, authenticated command context supplied by the server adapter, never by a model. */
public record Actor(UUID playerId, boolean operator) {
    public Actor {
        if (!operator) Objects.requireNonNull(playerId, "playerId");
    }

    public static Actor player(UUID playerId) {
        return new Actor(playerId, false);
    }

    public static Actor console() {
        return new Actor(null, true);
    }
}
