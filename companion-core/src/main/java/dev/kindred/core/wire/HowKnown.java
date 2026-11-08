package dev.kindred.core.wire;

/**
 * In plain terms: how a player came to know something: the game recorded it, it was told in a message built from
 * someone's own record, or someone only said so in words (which stays a claim until something confirms it).
 *
 * Technical: The {@code how_known} of F-31's event, staged here until the World Record is on main; the Wire marks what
 * an AI receiver learns {@link #TOLD}, or {@link #CLAIMED} when the message carries a person's words.
 */
public enum HowKnown { RECORDED, TOLD, CLAIMED }
