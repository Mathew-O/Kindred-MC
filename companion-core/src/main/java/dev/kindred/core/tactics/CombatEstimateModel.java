package dev.kindred.core.tactics;

/**
 * In plain terms: the combat maths a player uses to think ahead: how charged its swing is, how much damage a
 * hit would do to a given mob, and how a short plan of moves would turn out. It only predicts; it never
 * swings.
 *
 * Technical: Pure abstraction for estimating combat mechanics: charge timing, weapon damage,
 * critical strike eligibility, target mitigation, and multi-step sequence outcomes.
 */
public interface CombatEstimateModel {

    /**
     * Calculates attack cooldown progress (0.0 to 1.0) given attack speed and elapsed ticks.
     */
    double calculateAttackCharge(double attackSpeed, int elapsedTicksSinceAttackOrSwitch);

    /**
     * Calculates ticks required to reach full charge (charge >= 0.9).
     */
    int ticksToFullCharge(double attackSpeed);

    /**
     * Calculates predicted immediate damage interval dealt to target with the specified weapon and charge.
     */
    DamageInterval estimateStrikeDamage(WeaponProfile weapon, MobThreatObservation target, double charge, boolean isCritical);

    /**
     * Evaluates a full multi-step tactical sequence against the observed environment.
     */
    OutcomeEnvelope evaluateSequence(TacticalObservation obs, TacticalSequence sequence);
}
