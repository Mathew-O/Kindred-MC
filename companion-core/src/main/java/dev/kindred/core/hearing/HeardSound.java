package dev.kindred.core.hearing;

import java.util.Objects;

/**
 * In plain terms: one thing a player heard: what kind of sound, roughly which way, roughly how far, and when. Never
 * where exactly: a groan through a wall is "a hostile, far, to the left", no more (E-71f).
 *
 * Technical: an immutable observation for core, made in the adapter from what the server sent the body's connection
 * (or from the client's own rule for lava and water). {@code bearing} is relative to where the body faced when it
 * heard it.
 */
public record HeardSound(Kind kind, Bearing bearing, Band band, long tick) {
    public HeardSound {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(bearing);
        Objects.requireNonNull(band);
    }

    /** The sounds a player listens for; anything else is not kept. */
    public enum Kind { CREEPER_HISS, BOW_SHOT, HOSTILE, STEP, BLOCK_BROKEN, EXPLOSION, DOOR, CHEST, EATING, LAVA, WATER }

    /** Which way, in eighths of a turn, from where it faced. */
    public enum Bearing { AHEAD, AHEAD_RIGHT, RIGHT, BEHIND_RIGHT, BEHIND, BEHIND_LEFT, LEFT, AHEAD_LEFT }

    /** How far: near within 4 blocks, mid within 8, far beyond. */
    public enum Band { NEAR, MID, FAR }
}
