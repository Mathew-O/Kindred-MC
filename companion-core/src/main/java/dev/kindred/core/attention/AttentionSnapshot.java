package dev.kindred.core.attention;

import java.util.Objects;

/**
 * Immutable snapshot of an AI-player's current attention state and revision.
 *
 * @param tick server tick when this snapshot was evaluated
 * @param bodyGeneration body generation at evaluation time
 * @param revision monotonic attention revision, incremented on any state or operational change
 * @param state current attention state
 * @param optionalWorkActive true when operational conditions allow optional computation
 */
public record AttentionSnapshot(
        long tick,
        long bodyGeneration,
        long revision,
        AttentionState state,
        boolean optionalWorkActive) {

    public AttentionSnapshot {
        if (tick < 0) throw new IllegalArgumentException("tick cannot be negative: " + tick);
        if (bodyGeneration < 0) throw new IllegalArgumentException("bodyGeneration cannot be negative: " + bodyGeneration);
        if (revision < 0) throw new IllegalArgumentException("revision cannot be negative: " + revision);
        Objects.requireNonNull(state, "state");
    }
}
