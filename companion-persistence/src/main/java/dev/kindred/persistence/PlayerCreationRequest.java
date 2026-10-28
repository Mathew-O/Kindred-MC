package dev.kindred.persistence;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: a request to create a new native AI player: who controls it, its name (1 to 32 printable
 * characters), and the world and dimension it starts in.
 *
 * Technical: Explicit new AI identity, independent of how many other players its controller administers.
 */
public record PlayerCreationRequest(UUID operationId, UUID controllerId, String displayName, UUID worldId, String dimension) {
    public PlayerCreationRequest {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(controllerId);
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(displayName);
        if (displayName.isBlank() || displayName.length() > 32 || displayName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Player name must contain 1..32 printable characters");
        }
        if (dimension.length() > 256 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("A valid dimension identifier is required");
        }
    }
}
