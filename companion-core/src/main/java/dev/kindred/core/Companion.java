package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/** Legacy persisted identity/control envelope. Keep its layout for save compatibility; use PlayerIdentity in new planning code. */
public record Companion(
        UUID id,
        UUID ownerId,
        String displayName,
        UUID worldId,
        String dimension,
        UUID entityId,
        LifecycleState lifecycle,
        long intentRevision,
        long taskRevision,
        boolean stopLatched) {

    public Companion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(lifecycle, "lifecycle");
        displayName = requireText(displayName, "displayName");
        dimension = requireText(dimension, "dimension");
        if (intentRevision < 0 || taskRevision < 0) {
            throw new IllegalArgumentException("Revisions must be non-negative");
        }
    }

    public static Companion create(UUID id, UUID ownerId, String displayName, UUID worldId,
                                   String dimension) {
        return new Companion(id, ownerId, displayName, worldId, dimension, null,
                LifecycleState.SLEEPING, 0, 0, false);
    }

    public PlayerIdentity playerIdentity() { return new PlayerIdentity(id, worldId, displayName); }

    public Companion withLifecycle(LifecycleState state) {
        return new Companion(id, ownerId, displayName, worldId, dimension, entityId,
                state, intentRevision, taskRevision, stopLatched);
    }

    public Companion withDimension(String newDimension) {
        return new Companion(id, ownerId, displayName, worldId, requireText(newDimension, "dimension"), entityId,
                lifecycle, intentRevision, taskRevision, stopLatched);
    }

    public Companion withRevisions(long intent, long task) {
        if (intent < intentRevision || task < taskRevision) {
            throw new IllegalArgumentException("Revisions cannot move backwards");
        }
        return new Companion(id, ownerId, displayName, worldId, dimension, entityId,
                lifecycle, intent, task, stopLatched);
    }

    public Companion withEntityId(UUID entity) {
        return new Companion(id, ownerId, displayName, worldId, dimension, entity,
                lifecycle, intentRevision, taskRevision, stopLatched);
    }

    public Companion withStopLatched(boolean stopped) {
        return new Companion(id, ownerId, displayName, worldId, dimension, entityId,
                lifecycle, intentRevision, taskRevision, stopped);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
