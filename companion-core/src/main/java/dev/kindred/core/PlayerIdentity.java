package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/** The AI player's identity. Administrative access and team membership are separate concerns. */
public record PlayerIdentity(UUID id, UUID worldId, String name) {
    public PlayerIdentity {
        Objects.requireNonNull(id);
        Objects.requireNonNull(worldId);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Player name is required");
    }
}
