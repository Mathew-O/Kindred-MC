package dev.kindred.core;

/**
 * In plain terms: how long a player pauses before its message appears in chat: a short reply comes quickly
 * and a longer one takes longer (up to a limit), like someone typing.
 *
 * Technical: How long a companion waits before a line is heard. A short reply arrives quickly. A longer one
 * takes longer, up to a cap, so speech has the weight of the sentence instead of appearing as it is decided.
 */
public final class SpeechTiming {
    public static final long MIN_MILLIS = 250;
    public static final long MAX_MILLIS = 2_000;
    private static final long BASE_MILLIS = 180;
    private static final long PER_CHARACTER_MILLIS = 32;

    private SpeechTiming() { }

    /** The pause before this message appears. */
    public static long delayMillis(String spoken) {
        int length = spoken == null ? 0 : spoken.strip().length();
        long delay = BASE_MILLIS + PER_CHARACTER_MILLIS * (long) length;
        if (delay < MIN_MILLIS) return MIN_MILLIS;
        return Math.min(MAX_MILLIS, delay);
    }
}
