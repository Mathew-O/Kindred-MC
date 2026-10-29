package dev.kindred.model;

import dev.kindred.core.survival.StrategyAdvice;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: the shape every optional AI adviser has, whichever company serves it. It only ever gives
 * advice picked from fixed lists plus a little text; it never controls a player directly or writes code, and
 * nothing in the game waits for it.
 *
 * Technical: An optional model strategist (F-19b, F-19d): one request per consultation, off the game thread,
 * whose reply is a stance and preferences from fixed sets and two short texts, validated in core. It never
 * returns actions or code, and nothing in the game waits on it. Each provider reads its own key from the
 * environment; keys are never stored or logged.
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

    /** The chat lane's bounds (AI-05, E-20a): an eight-second deadline, a sentence or two, one chat line. */
    Duration CHAT_DEADLINE = Duration.ofSeconds(8);
    int CHAT_MAX_OUTPUT_TOKENS = 120;
    int MAX_SAID = 240;

    /**
     * Someone spoke to the player (E-20a): who it is and how it talks, in {@code system}; what it knows that this
     * listener may know, and the message quoted as data, in {@code text}; and whether this listener may be told
     * where things are, which decides whether a reply may give a place.
     */
    record Conversation(String system, String text, boolean mayGivePlaces) {
        public Conversation {
            Objects.requireNonNull(system);
            Objects.requireNonNull(text);
        }
    }

    /**
     * What a conversation came to: the line to say, if the reply was usable; why it was said; the tokens and time it
     * took; and why it failed, if it did. A refused reply is a failure: nothing of it is said, and the player's
     * fixed reply is used instead.
     */
    record Line(Optional<String> say, String reason, long inputTokens, long cachedTokens, long outputTokens, long millis,
                Optional<String> failure) {
        static Line failed(String why, long millis) { return new Line(Optional.empty(), "", 0, 0, 0, millis, Optional.of(why)); }

        static Line failed(String why, long in, long cached, long out, long millis) {
            return new Line(Optional.empty(), "", in, cached, out, millis, Optional.of(why));
        }

        /** Words no player says in chat while every listener gets clean speech (E-18's presets come later). */
        private static final java.util.regex.Pattern UNCLEAN = java.util.regex.Pattern.compile(
                "\\b(fuck\\w*|shit\\w*|bitch\\w*|cunts?|bastards?|assholes?|dicks?|piss\\w*|damn\\w*|crap\\w*|sluts?|whores?|fags?|retard\\w*)\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        /**
         * The reply checked before anything of it is said: one line of at most {@link #MAX_SAID} characters, not a
         * command, without formatting codes or control characters, clean, and giving no place to a listener who may
         * not know where things are.
         */
        public static Line checked(Conversation conversation, String say, String reason, long in, long cached, long out, long millis) {
            String why = reason == null ? "" : reason.strip();
            if (why.length() > 300) why = why.substring(0, 300);
            String said = say == null ? "" : say.strip();
            String refused = said.isEmpty() ? "nothing to say"
                    : said.length() > MAX_SAID ? "too long"
                    : said.indexOf('\n') >= 0 || said.indexOf('\r') >= 0 ? "more than one line"
                    : said.startsWith("/") ? "a command"
                    : said.indexOf('§') >= 0 ? "formatting codes"
                    : said.chars().anyMatch(Character::isISOControl) ? "control characters"
                    : UNCLEAN.matcher(said).find() ? "not clean"
                    : !conversation.mayGivePlaces() && dev.kindred.core.ConversationAudience.givesPlace(said) ? "a place this listener may not know"
                    : null;
            if (refused != null) return new Line(Optional.empty(), why, in, cached, out, millis, Optional.of(refused));
            return new Line(Optional.of(said), why, in, cached, out, millis, Optional.empty());
        }
    }

    /** Answer someone who spoke to the player; a provider that cannot says so, and the fixed reply is used. */
    default CompletableFuture<Line> converse(Conversation conversation) {
        return CompletableFuture.completedFuture(Line.failed("conversation not supported by " + provider(), 0));
    }

    /** Tokens to reserve before a conversation: its input, conservatively, and the most the reply may use. */
    static long reservation(Conversation conversation) {
        return (conversation.system().length() + conversation.text().length()) / 3 + 100 + CHAT_MAX_OUTPUT_TOKENS;
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
