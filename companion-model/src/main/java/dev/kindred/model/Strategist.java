package dev.kindred.model;

import dev.kindred.core.survival.StrategyAdvice;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
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

    /** A question's own bound on the reply: one choice, a name at most, and a sentence why. */
    int ASK_MAX_OUTPUT_TOKENS = 300;

    /**
     * A single question apart from the planning lane's advice (E-44, E-16d): what the player is to decide, in
     * {@code system} and {@code text}; the answers it may give, in {@code choices}, or none when it asks for a name of
     * at most {@code maxText} characters.
     */
    record Question(String system, String text, List<String> choices, int maxText) {
        public Question {
            Objects.requireNonNull(system);
            Objects.requireNonNull(text);
            choices = List.copyOf(choices);
            if (maxText < 0 || maxText > 64) throw new IllegalArgumentException("A name is at most 64 characters");
        }
    }

    /**
     * What a question came to: the choice made, one of those offered; the text asked for; why; the tokens and time
     * it took; and why it failed, if it did. A reply that is not one of the choices, or whose text is too long, is a
     * failure: nothing of it is used.
     */
    record Answer(Optional<String> choice, Optional<String> text, String reason, long inputTokens, long cachedTokens, long outputTokens,
                  long millis, Optional<String> failure) {
        static Answer failed(String why, long millis) {
            return new Answer(Optional.empty(), Optional.empty(), "", 0, 0, 0, millis, Optional.of(why));
        }

        /** The reply checked against what was asked. */
        static Answer checked(Question question, String choice, String text, String reason, long in, long cached, long out, long millis) {
            String why = reason == null ? "" : reason.strip();
            if (why.length() > 300) why = why.substring(0, 300);
            Optional<String> picked = Optional.empty();
            if (!question.choices().isEmpty()) {
                String said = choice == null ? "" : choice.strip();
                picked = question.choices().stream().filter(offered -> offered.equalsIgnoreCase(said)).findFirst();
                if (picked.isEmpty()) return new Answer(Optional.empty(), Optional.empty(), why, in, cached, out, millis, Optional.of("not one of the choices: " + said));
            }
            Optional<String> named = Optional.empty();
            if (question.maxText() > 0) {
                String given = text == null ? "" : text.strip();
                if (given.isEmpty() || given.length() > question.maxText()) {
                    return new Answer(picked, Optional.empty(), why, in, cached, out, millis, Optional.of("no usable text"));
                }
                named = Optional.of(given);
            }
            return new Answer(picked, named, why, in, cached, out, millis, Optional.empty());
        }
    }

    /** Ask one question; a provider that cannot answers that it cannot, and the player decides by its own rule. */
    default CompletableFuture<Answer> ask(Question question) {
        return CompletableFuture.completedFuture(Answer.failed("questions not supported by " + provider(), 0));
    }

    /** Tokens to reserve before asking {@code question}: its input, conservatively, and the most the reply may use. */
    static long reservation(Question question) {
        return (question.system().length() + question.text().length()) / 3 + 100 + ASK_MAX_OUTPUT_TOKENS;
    }

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
