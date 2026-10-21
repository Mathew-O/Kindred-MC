package dev.kindred.model;

import dev.kindred.core.survival.StrategyAdvice;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * An optional model strategist (F-19b, F-19d): one request per consultation, off the game thread, whose reply is a
 * stance and preferences from fixed sets and two short texts, validated in core. It never returns actions or code,
 * and nothing in the game waits on it. Each provider reads its own key from the environment; keys are never stored or
 * logged.
 */
public interface Strategist extends AutoCloseable {
    /** The planning lane's bounds (AI-05): a fifteen-second deadline and a thousand output tokens. */
    Duration DEADLINE = Duration.ofSeconds(15);
    int MAX_OUTPUT_TOKENS = 1000;

    /**
     * What a consultation came to: advice if the reply was usable, the tokens it cost (all input, of which
     * {@code cachedTokens} were read from the prompt cache, and output), how long the request took, and why it failed
     * if it did.
     */
    record Outcome(Optional<StrategyAdvice> advice, long inputTokens, long cachedTokens, long outputTokens, long millis,
                   Optional<String> failure) {
        static Outcome failed(String why, long millis) { return new Outcome(Optional.empty(), 0, 0, 0, millis, Optional.of(why)); }
    }

    /** Ask for advice on {@code report}; places in the reply must lie near {@code reported}. */
    CompletableFuture<Outcome> advise(String report, List<StrategyAdvice.Place> reported);

    /** Who serves it, for the log: "Anthropic", "OpenAI", ... */
    String provider();

    String model();

    /** The request settings, for the log. */
    String pace();

    @Override
    void close();

    /**
     * Tokens to reserve before asking about {@code report}: a conservative count of the input (three characters
     * a token, plus the reply schema) and the most the reply may use.
     */
    static long reservation(String report) {
        return (StrategistPrompt.SYSTEM.length() + report.length()) / 3 + 400 + MAX_OUTPUT_TOKENS;
    }
}
