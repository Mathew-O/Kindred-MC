package dev.kindred.core.tactics;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: one possible fight plan, with how it is expected to turn out and a short description.
 *
 * Technical: A concrete evaluated tactical candidate with its sequence, predicted outcome envelope,
 * and explanatory description.
 */
public record TacticalOption(
        UUID optionId,
        TacticalSequence sequence,
        OutcomeEnvelope outcome,
        String description
) {
    public TacticalOption {
        Objects.requireNonNull(optionId, "optionId");
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(description, "description");
    }
}
