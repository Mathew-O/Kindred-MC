package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/** One-use confirmation that a removal is really the one the owner just asked for. */
public record RemovalToken(UUID token, UUID companionId, UUID actorId, long expiresAt) {
    public RemovalToken {
        Objects.requireNonNull(token);
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(actorId);
    }

    public boolean matches(UUID presented, UUID companion, UUID actor, long now) {
        return token.equals(presented) && companionId.equals(companion) && actorId.equals(actor) && now < expiresAt;
    }
}
