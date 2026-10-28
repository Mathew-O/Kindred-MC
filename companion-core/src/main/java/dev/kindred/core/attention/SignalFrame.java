package dev.kindred.core.attention;

import java.util.Objects;

/**
 * In plain terms: the danger signals gathered for a player each tick: whether its body is working, whether it
 * stands in danger (fire, lava, cactus), whether a threat is in sight, whether it was just attacked and
 * whether it urgently needs food or health.
 *
 * Technical: Immutable per-tick observation inputs delivered from the server thread to the attention state
 * machine.
 *
 * @param tick monotonic server tick
 * @param bodyGeneration monotonic body/connection generation
 * @param operational true when player body is loaded, connected and free from reconciliation/stop faults
 * @param environmentalDanger true when immediate environmental hazard (e.g. fire, lava, cactus) is present
 * @param threat credible visible threat knowledge
 * @param threatObservedTick server tick when the threat observation was captured
 * @param attackSequence newly observed attributed attack event sequence within this generation (null if none)
 * @param recoveryNeeded true when urgent recovery need (e.g. food/health) exists
 */
public record SignalFrame(
        long tick,
        long bodyGeneration,
        boolean operational,
        boolean environmentalDanger,
        ThreatKnowledge threat,
        long threatObservedTick,
        Long attackSequence,
        boolean recoveryNeeded) {

    public SignalFrame {
        if (tick < 0) throw new IllegalArgumentException("tick cannot be negative: " + tick);
        if (bodyGeneration < 0) throw new IllegalArgumentException("bodyGeneration cannot be negative: " + bodyGeneration);
        Objects.requireNonNull(threat, "threat");
        if (threatObservedTick < 0) throw new IllegalArgumentException("threatObservedTick cannot be negative: " + threatObservedTick);
    }
}
