package dev.kindred.core.tactics;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: a short fight plan of up to three moves (such as get the sword ready, close in, then
 * strike) aimed at one mob or a safe spot.
 *
 * Technical: A short tactical action sequence (up to 3 steps) targeted at an entity or safe position.
 */
public record TacticalSequence(
        List<ActionStep> steps,
        UUID targetEntityId,
        WeaponProfile weapon
) {
    public static final int MAX_STEPS = 3;

    public TacticalSequence {
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps cannot be empty");
        }
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("steps cannot exceed " + MAX_STEPS + ": " + steps.size());
        }
        steps = List.copyOf(steps);
    }

    /** A plan of one move. */
    public static TacticalSequence single(ActionStep step, UUID targetEntityId, WeaponProfile weapon) {
        return new TacticalSequence(List.of(step), targetEntityId, weapon);
    }

    /** A plan of two moves. */
    public static TacticalSequence pair(ActionStep first, ActionStep second, UUID targetEntityId, WeaponProfile weapon) {
        return new TacticalSequence(List.of(first, second), targetEntityId, weapon);
    }

    /** A plan of three moves. */
    public static TacticalSequence triple(ActionStep first, ActionStep second, ActionStep third, UUID targetEntityId, WeaponProfile weapon) {
        return new TacticalSequence(List.of(first, second, third), targetEntityId, weapon);
    }

    /** Whether the plan includes a certain move. */
    public boolean containsStep(ActionStep step) {
        return steps.contains(step);
    }
}
