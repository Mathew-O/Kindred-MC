package dev.kindred.core.expedition;

/**
 * Triggers determining when an exploring AI player must return to home shelter.
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
