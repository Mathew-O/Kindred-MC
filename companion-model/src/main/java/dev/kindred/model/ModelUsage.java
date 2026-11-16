package dev.kindred.model;

/**
 * In plain terms: what one call to a model cost, as the log says it: every input token, how many were read from the
 * prompt cache and how many written to it, the output, and how long it took.
 *
 * Technical: F-19g. Log only, one line a stance, voice or page call, so a run can show whether the cache works. A
 * write the provider did not report ({@link Strategist#NOT_REPORTED}, the OpenAI-compatible path) is "n/a", never 0;
 * so is a read, where the provider has never reported one ({@link Strategist#reportsCacheReads}, F-19f).
 */
public final class ModelUsage {
    private ModelUsage() { }

    public static String line(long in, long cacheRead, long cacheWrite, long out, long millis) {
        return line(in, cacheRead, true, cacheWrite, out, millis);
    }

    /** As {@link #line(long, long, long, long, long)}, the read "n/a" when {@code readReported} is false. */
    public static String line(long in, long cacheRead, boolean readReported, long cacheWrite, long out, long millis) {
        return "in " + in + ", cache read " + (readReported ? String.valueOf(cacheRead) : "n/a") + ", cache write "
                + (cacheWrite < 0 ? "n/a" : String.valueOf(cacheWrite)) + ", out " + out + " tokens, " + millis + " ms";
    }

    public static String of(Strategist.Outcome o) { return line(o.inputTokens(), o.cachedTokens(), o.cacheWriteTokens(), o.outputTokens(), o.millis()); }

    public static String of(Strategist.Line l) { return line(l.inputTokens(), l.cachedTokens(), l.cacheWriteTokens(), l.outputTokens(), l.millis()); }

    public static String of(Strategist.Page p) { return line(p.inputTokens(), p.cachedTokens(), p.cacheWriteTokens(), p.outputTokens(), p.millis()); }

    /** A stance call's line, its read "n/a" where {@code by} has never reported one. */
    public static String of(Strategist.Outcome o, Strategist by) {
        return line(o.inputTokens(), o.cachedTokens(), by.reportsCacheReads(), o.cacheWriteTokens(), o.outputTokens(), o.millis());
    }

    /** A voice call's line, as above. */
    public static String of(Strategist.Line l, Strategist by) {
        return line(l.inputTokens(), l.cachedTokens(), by.reportsCacheReads(), l.cacheWriteTokens(), l.outputTokens(), l.millis());
    }

    /** A chronicle call's line, as above. */
    public static String of(Strategist.Page p, Strategist by) {
        return line(p.inputTokens(), p.cachedTokens(), by.reportsCacheReads(), p.cacheWriteTokens(), p.outputTokens(), p.millis());
    }
}
