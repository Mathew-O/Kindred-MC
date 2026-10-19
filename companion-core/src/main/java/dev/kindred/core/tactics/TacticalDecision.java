package dev.kindred.core.tactics;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable outcome of a tactical priority policy evaluation.
 * Holds the selected equivalent frontier, highest achieved objective, and rejection reasons.
 */
public record TacticalDecision(
        UUID decisionId,
        long tick,
        long bodyGeneration,
        TacticalObjective selectedObjective,
        SurvivalClass achievedSurvivalClass,
        List<TacticalOption> winningFrontier,
        Map<UUID, String> rejectedReasons
) {
    public TacticalDecision {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(achievedSurvivalClass, "achievedSurvivalClass");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative: " + tick);
        }
        if (bodyGeneration < 0) {
            throw new IllegalArgumentException("bodyGeneration must be non-negative: " + bodyGeneration);
        }
        winningFrontier = winningFrontier == null ? List.of() : List.copyOf(winningFrontier);
        rejectedReasons = rejectedReasons == null ? Map.of() : Collections.unmodifiableMap(rejectedReasons);
    }

    public Optional<TacticalOption> bestOption() {
        return winningFrontier.isEmpty() ? Optional.empty() : Optional.of(winningFrontier.get(0));
    }

    public boolean hasViableOption() {
        return !winningFrontier.isEmpty() && achievedSurvivalClass != SurvivalClass.INELIGIBLE;
    }
}
