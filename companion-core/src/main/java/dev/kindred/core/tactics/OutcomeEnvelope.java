package dev.kindred.core.tactics;

import java.util.Objects;

/**
 * Evaluated multi-dimensional outcome envelope of a tactical sequence.
 * Includes survival classification, predicted damage intervals, execution timing,
 * collateral risk, and objective-prevented harm.
 */
public record OutcomeEnvelope(
        SurvivalClass survivalClass,
        DamageInterval dealtDamage,
        DamageInterval receivedSelfDamage,
        TimeInterval executionTime,
        boolean killsTargetReliably,
        boolean escapesHazardReliably,
        boolean collateralRisk,
        TacticalObjective primaryObjective,
        double preventedHarmScore,
        double resourceCost,
        boolean safeContinuation
) {
    public OutcomeEnvelope {
        Objects.requireNonNull(survivalClass, "survivalClass");
        Objects.requireNonNull(dealtDamage, "dealtDamage");
        Objects.requireNonNull(receivedSelfDamage, "receivedSelfDamage");
        Objects.requireNonNull(executionTime, "executionTime");
        Objects.requireNonNull(primaryObjective, "primaryObjective");
        if (!Double.isFinite(preventedHarmScore) || preventedHarmScore < 0) {
            throw new IllegalArgumentException("preventedHarmScore must be finite and >= 0: " + preventedHarmScore);
        }
        if (!Double.isFinite(resourceCost) || resourceCost < 0) {
            throw new IllegalArgumentException("resourceCost must be finite and >= 0: " + resourceCost);
        }
    }
}
