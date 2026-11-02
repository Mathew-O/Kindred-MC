package dev.kindred.core.social;

import java.util.Locale;
import java.util.Optional;

/**
 * In plain terms: how a player sounds when it talks: plainly, a little dry, or playful (jokes, callbacks, and light
 * teasing of other AI players only). Its controller picks one; the server's operator sets the most any player may be.
 * Every one is clean: no swearing, flirting or dark jokes, whoever is listening.
 *
 * Technical: E-18a's expression presets. Ordered plain, dry, playful; a server cap bounds every preset to at most its own
 * rank. A player with no preset set keeps the rule from before presets: playful from humour {@value #PLAYFUL_HUMOR} up,
 * plain below it.
 */
public enum Expression {
    PLAIN, DRY, PLAYFUL;

    /** The humour from which a player with no preset of its own sounds playful, as every player did before presets. */
    public static final int PLAYFUL_HUMOR = 65;

    /** This preset bounded by the server's cap: the less expressive of the two. */
    public Expression capped(Expression cap) {
        return cap == null || ordinal() <= cap.ordinal() ? this : cap;
    }

    /** The preset a player with none set sounds by: its humour decides, as before presets. */
    public static Expression byHumor(int humor) {
        return humor >= PLAYFUL_HUMOR ? PLAYFUL : PLAIN;
    }

    /** The preset named {@code plain}, {@code dry} or {@code playful}, any case; empty for anything else. */
    public static Optional<Expression> parse(String name) {
        if (name == null) return Optional.empty();
        for (Expression expression : values()) {
            if (expression.name().equals(name.strip().toUpperCase(Locale.ROOT))) return Optional.of(expression);
        }
        return Optional.empty();
    }

    /** The preset's name as a command takes it: {@code plain}, {@code dry} or {@code playful}. */
    public String id() { return name().toLowerCase(Locale.ROOT); }

    /**
     * The one line the voice is told about how to speak to this listener (E-18a, E-19a): the preset, bounded by what the
     * listener (or the strictest of everyone who will hear the line) has asked for. A person is never teased.
     */
    public static String voiceLine(Expression effective, ListenerPreferences heard) {
        boolean noJokes = heard != null && heard.noJokes();
        String line = switch (effective == null ? PLAIN : effective) {
            case PLAIN -> "plainly, without jokes";
            case DRY -> "dry and understated, without jokes";
            case PLAYFUL -> noJokes ? "plainly, without jokes (they asked for none)"
                    : "playful: a light joke or a callback when it fits, never teasing a person";
        };
        return "How you speak to them: " + line + "; clean words only.";
    }
}
