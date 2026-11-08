package dev.kindred.core.factions;

/**
 * In plain terms: what kind of ground a place is in a faction world: a safe zone (spawn, the market), the wilderness,
 * or land a faction has claimed.
 *
 * Technical: The zone a rule is asked about (M-06c to M-06f). Safe zones are absolute: no PvP, no claims, no captures.
 */
public enum Zone { SAFE, WILDERNESS, CLAIMED }
