package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: the saved record of an AI player from the older companion design: id, owner, name, world,
 * dimension, state and whether it was told to stop. Its layout is kept so old saves still load; newer code
 * uses PlayerIdentity instead.
 *
 * Technical: Legacy persisted identity/control envelope. Keep its layout for save compatibility; use
 * PlayerIdentity in new planning code.
 */
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

    /** A brand-new record, asleep and not yet spawned. */
    public static Companion create(UUID id, UUID ownerId, String displayName, UUID worldId,
                                   String dimension) {
        return new Companion(id, ownerId, displayName, worldId, dimension, null,
                LifecycleState.SLEEPING, 0, 0, false);
    }

    public PlayerIdentity playerIdentity() { return new PlayerIdentity(id, worldId, displayName); }

    /** The same record in a different state (awake, sleeping and so on). */
    public Companion withLifecycle(LifecycleState state) {
        return new Companion(id, ownerId, displayName, worldId, dimension, entityId,
                state, intentRevision, taskRevision, stopLatched);
    }

    /** The same record in a different dimension. */
    public Companion withDimension(String newDimension) {
        return new Companion(id, ownerId, displayName, worldId, requireText(newDimension, "dimension"), entityId,
                lifecycle, intentRevision, taskRevision, stopLatched);
    }

    /** The same record with updated change counters. */
    public Companion withRevisions(long intent, long task) {
        if (intent < intentRevision || task < taskRevision) {
            throw new IllegalArgumentException("Revisions cannot move backwards");
        }
        return new Companion(id, ownerId, displayName, worldId, dimension, entityId,
                lifecycle, intent, task, stopLatched);
    }

    /** The same record linked to a body in the world. */
    public Companion withEntityId(UUID entity) {
        return new Companion(id, ownerId, displayName, worldId, dimension, entity,
                lifecycle, intentRevision, taskRevision, stopLatched);
    }

    /** The same record with the stop switch on or off. */
    public Companion withStopLatched(boolean stopped) {
        return new Companion(id, ownerId, displayName, worldId, dimension, entityId,
                lifecycle, intentRevision, taskRevision, stopped);
    }

    /** Checks a text field is filled in. */
    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
