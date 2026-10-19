package dev.kindred.core.attention;

import java.util.Objects;
import java.util.UUID;

/**
 * Single-use token issued by {@link AttentionWorkScheduler} authorizing a single slice of optional
 * computation.
 *
 * <p>A token authorizes bounded observation and planning opportunities, NEVER a Minecraft physical
 * mutation or action lease. Tokens expire at {@link #expiresTick()} and are invalidated by any body
 * replacement, attention revision change or superseded request.
 */
public record WorkToken(
        UUID runtimeSession,
        UUID playerId,
        long bodyGeneration,
        long attentionRevision,
        WorkKind kind,
        long invalidationRevision,
        long requestSequence,
        long issuedTick,
        long expiresTick) {

    public WorkToken {
        Objects.requireNonNull(runtimeSession, "runtimeSession");
        Objects.requireNonNull(playerId, "playerId");
        if (bodyGeneration < 0) throw new IllegalArgumentException("bodyGeneration cannot be negative: " + bodyGeneration);
        if (attentionRevision < 0) throw new IllegalArgumentException("attentionRevision cannot be negative: " + attentionRevision);
        Objects.requireNonNull(kind, "kind");
        if (invalidationRevision < 0) throw new IllegalArgumentException("invalidationRevision cannot be negative: " + invalidationRevision);
        if (requestSequence < 0) throw new IllegalArgumentException("requestSequence cannot be negative: " + requestSequence);
        if (issuedTick < 0) throw new IllegalArgumentException("issuedTick cannot be negative: " + issuedTick);
        if (expiresTick < issuedTick) throw new IllegalArgumentException("expiresTick cannot precede issuedTick");
    }
}
