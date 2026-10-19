package dev.kindred.persistence;

import java.util.Objects;
import java.util.UUID;

/** Trusted command context; display names never authorize an operation. */
public record CreationRequest(UUID operationId, UUID ownerId, String displayName, UUID worldId, String dimension) {
    public CreationRequest {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(dimension);
        if (displayName.isBlank() || displayName.length() > 32) {
            throw new IllegalArgumentException("Companion name must contain 1 to 32 characters");
        }
        if (dimension.isBlank()) throw new IllegalArgumentException("Dimension is required");
    }
}
