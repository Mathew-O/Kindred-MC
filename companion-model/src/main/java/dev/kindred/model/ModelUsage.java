package dev.kindred.model;

/**
 * In plain terms: what one call to a model cost, as the log says it: every input token, how many were read from the
 * prompt cache and how many written to it, the output, and how long it took.
 *
 * Technical: F-19g. Log only, one line a stance, voice or page call, so a run can show whether the cache works. A
 * write the provider did not report ({@link Strategist#NOT_REPORTED}, the OpenAI-compatible path) is "n/a", never 0.
 */
public final class ModelUsage {
    private ModelUsage() { }

    public static String line(long in, long cacheRead, long cacheWrite, long out, long millis) {
        return "in " + in + ", cache read " + cacheRead + ", cache write " + (cacheWrite < 0 ? "n/a" : String.valueOf(cacheWrite))
                + ", out " + out + " tokens, " + millis + " ms";
    }

    public static String of(Strategist.Outcome o) { return line(o.inputTokens(), o.cachedTokens(), o.cacheWriteTokens(), o.outputTokens(), o.millis()); }

    public static String of(Strategist.Line l) { return line(l.inputTokens(), l.cachedTokens(), l.cacheWriteTokens(), l.outputTokens(), l.millis()); }

    public static String of(Strategist.Page p) { return line(p.inputTokens(), p.cachedTokens(), p.cacheWriteTokens(), p.outputTokens(), p.millis()); }
}
