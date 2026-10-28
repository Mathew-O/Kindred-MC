package dev.kindred.core.expedition;

/**
 * In plain terms: where a player is on a trip: at home, heading out, or on its way back.
 *
 * Technical: Lifecycle states of an AI player's expedition.
 */
public enum ExpeditionStatus {
    IDLE_AT_BASE,
    EXPLORING_OUTWARD,
    RETURNING
}
