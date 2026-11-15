package dev.kindred.core.metrics;

/**
 * In plain terms: how long the server's ticks took since the cost line was last written: how many, their mean, the
 * tick that 99 in 100 came in under, how many went over the 50 ms a tick has, and the longest and when it was.
 *
 * Technical: M-06k7, the month run's pre-flight. Measured, never estimated: each tick's own time is added once. The 99th
 * percentile is read off a histogram of whole milliseconds (to 2,000 ms, longer ones in one last bucket), so it is exact
 * to the millisecond below that and "over 2000" above it. Fixed size whatever the run's length. Not thread-safe: it is
 * fed and read on the server thread.
 */
public final class TickWindow {
    /** The highest whole millisecond the histogram keeps apart; longer ticks share its last bucket. */
    public static final int MAX_MS = 2_000;
    private static final long MS = 1_000_000L;

    private final long[] buckets = new long[MAX_MS + 2];
    private long count, totalNanos, over50, longestNanos, longestTick = -1;

    /** One tick that took {@code nanos}, the server's tick {@code tick}. */
    public void add(long nanos, long tick) {
        if (nanos < 0) return;
        count++;
        totalNanos += nanos;
        if (nanos > 50 * MS) over50++;
        if (nanos > longestNanos) {
            longestNanos = nanos;
            longestTick = tick;
        }
        int ms = (int) Math.min(MAX_MS + 1, nanos / MS);
        buckets[ms]++;
    }

    public long count() { return count; }

    /** The mean tick, in milliseconds; 0 with none. */
    public double meanMs() { return count == 0 ? 0 : totalNanos / (double) count / MS; }

    /** The whole millisecond 99 in 100 ticks took no longer than (its bucket's upper edge); {@code MAX_MS + 1} meaning longer than MAX_MS. */
    public int p99Ms() {
        if (count == 0) return 0;
        long need = (long) Math.ceil(count * 0.99), seen = 0;
        for (int ms = 0; ms < buckets.length; ms++) {
            seen += buckets[ms];
            if (seen >= need) return ms + 1 > MAX_MS ? MAX_MS + 1 : ms + 1;
        }
        return MAX_MS + 1;
    }

    public long over50() { return over50; }

    public double longestMs() { return longestNanos / (double) MS; }

    public long longestTick() { return longestTick; }

    /** For the cost line: {@code ticks 24000: mean 23.6 ms, p99 61 ms, 312 over 50 ms, longest 1130.2 ms (tick 90211)}. */
    public String describe() {
        if (count == 0) return "ticks 0";
        int p99 = p99Ms();
        return String.format(java.util.Locale.ROOT, "ticks %d: mean %.1f ms, p99 %s ms, %d over 50 ms, longest %.1f ms (tick %d)",
                count, meanMs(), p99 > MAX_MS ? "over " + MAX_MS : String.valueOf(p99), over50, longestMs(), longestTick);
    }

    /** Begins a new window. */
    public void reset() {
        java.util.Arrays.fill(buckets, 0);
        count = totalNanos = over50 = longestNanos = 0;
        longestTick = -1;
    }
}
