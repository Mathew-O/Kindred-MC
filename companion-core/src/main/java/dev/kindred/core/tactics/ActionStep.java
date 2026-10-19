package dev.kindred.core.tactics;

/**
 * Elementary action steps that comprise a bounded tactical sequence.
 */
public enum ActionStep {
    /**
     * Stay in place, hold ground / guard, and reassess next tick.
     */
    STAY_AND_GUARD,

    /**
     * Disengage and move to a verified safe local position.
     */
    RETREAT_TO_SAFE_CORRIDOR,

    /**
     * Approach target through a verified corridor into attack range.
     */
    APPROACH_TARGET,

    /**
     * Execute a standard grounded attack with the prepared weapon.
     */
    NORMAL_STRIKE,

    /**
     * Execute an elevated falling critical strike (requires jump setup).
     */
    CRITICAL_STRIKE,

    /**
     * Switch held slot to prepare a specified owned weapon.
     */
    PREPARE_WEAPON
}
