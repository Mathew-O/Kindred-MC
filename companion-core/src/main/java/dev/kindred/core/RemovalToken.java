package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: a one-time confirmation code for deleting an AI player, so a removal only happens when its
 * owner really just asked for it.
 *
 * Technical: One-use confirmation that a removal is really the one the owner just asked for.
 */
public record RemovalToken(UUID token, UUID companionId, UUID actorId, long expiresAt) {
    public RemovalToken {
        Objects.requireNonNull(token);
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(actorId);
    }

    /** Whether a confirmation is this one, for the same player and person, and not yet expired. */
    public boolean matches(UUID presented, UUID companion, UUID actor, long now) {
        return token.equals(presented) && companionId.equals(companion) && actorId.equals(actor) && now < expiresAt;
    }
}
