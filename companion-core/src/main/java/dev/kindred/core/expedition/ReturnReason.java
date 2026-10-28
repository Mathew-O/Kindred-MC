package dev.kindred.core.expedition;

/**
 * In plain terms: why an exploring player heads home: dusk, night, a thunderstorm, full bags, low health, low
 * food, being too far away, or being called back.
 *
 * Technical: Triggers determining when an exploring AI player must return to home shelter.
 */
public enum ReturnReason {
    NONE,
    DUSK_APPROACHING,
    NIGHT,
    THUNDERSTORM,
    INVENTORY_FULL,
    LOW_HEALTH,
    LOW_RATIONS,
    MAX_RADIUS_REACHED,
    OPERATOR_RECALL
}
