package dev.kindred.core.social;

import dev.kindred.core.Personality;

import java.util.Locale;
import java.util.Optional;

/**
 * In plain terms: how a player sounds when it talks: plainly, a little dry, playful (jokes, callbacks, and light teasing of
 * other AI players only), or rough (swearing, dark jokes about dying in the game, and flirting with other AI players). Its
 * controller picks one; the server's operator sets the most any player may be. Whatever a player's preset, a person hears
 * only what they asked for: swearing and dark jokes only if they said yes to them (or on a server whose operator said yes
 * for everyone), flirting only by their own yes, and never anything {@link Decency} blocks.
 *
 * Technical: E-18a's presets and E-18b's rough one. Ordered plain, dry, playful, rough; a server cap bounds every preset to
 * at most its own rank. A player with no preset set sounds by its personality ({@link #byPersonality}): playful from
 * humour {@value #PLAYFUL_HUMOR} up, as before presets, and rough with that humour and combativeness from
 * {@value #ROUGH_COMBATIVENESS} up.
 */
public enum Expression {
    PLAIN, DRY, PLAYFUL, ROUGH;

    /** The humour from which a player with no preset of its own sounds playful, as every player did before presets. */
    public static final int PLAYFUL_HUMOR = 65;
    /** With playful humour, the combativeness from which a player with no preset sounds rough (E-18b). */
    public static final int ROUGH_COMBATIVENESS = 80;

    /** This preset bounded by the server's cap: the less expressive of the two. */
    public Expression capped(Expression cap) {
        return cap == null || ordinal() <= cap.ordinal() ? this : cap;
    }

    /** The preset a player with none set sounded by before E-18b: its humour alone. */
    public static Expression byHumor(int humor) {
        return humor >= PLAYFUL_HUMOR ? PLAYFUL : PLAIN;
    }

    /** The preset a player with none set sounds by: playful by its humour, rough if it is combative too (Sol). */
    public static Expression byPersonality(Personality personality) {
        if (personality == null) return PLAIN;
        Expression byHumor = byHumor(personality.humor());
        return byHumor == PLAYFUL && personality.combativeness() >= ROUGH_COMBATIVENESS ? ROUGH : byHumor;
    }

    /**
     * Whether a player flirts, with another AI player and with a person who asked for it: a sociable one with some humour
     * (Kindred). A personality, never a permission: who hears it is the listener's to say.
     */
    public static boolean flirty(Personality personality) {
        return personality != null && personality.sociability() >= 75 && personality.humor() >= 55;
    }

    /** The preset named {@code plain}, {@code dry}, {@code playful} or {@code rough}, any case; empty for anything else. */
    public static Optional<Expression> parse(String name) {
        if (name == null) return Optional.empty();
        for (Expression expression : values()) {
            if (expression.name().equals(name.strip().toUpperCase(Locale.ROOT))) return Optional.of(expression);
        }
        return Optional.empty();
    }

    /** The preset's name as a command takes it. */
    public String id() { return name().toLowerCase(Locale.ROOT); }

    /**
     * The one line the voice is told about how to speak to this listener (E-18a, E-19a): the preset, bounded by what the
     * listener (or the strictest of everyone who will hear the line) has asked for. A person is never teased.
     */
    public static String voiceLine(Expression effective, ListenerPreferences heard) {
        return voiceLine(effective, heard, false, null);
    }

    /**
     * As {@link #voiceLine(Expression, ListenerPreferences)}, with whether the player flirts and how it types (E-18b).
     * Swearing and dark jokes only if this listener said yes; flirting only if they said yes and the player is flirty; and
     * never a slur, anything sexual, an insult or a threat to the person, or a joke about real-world harm.
     */
    public static String voiceLine(Expression effective, ListenerPreferences heard, boolean flirty, ChatStyle style) {
        boolean noJokes = heard != null && heard.noJokes();
        Expression sounds = effective == null ? PLAIN : effective;
        String line = switch (sounds) {
            case PLAIN -> "plainly, without jokes";
            case DRY -> "dry and understated, without jokes";
            case PLAYFUL -> noJokes ? "plainly, without jokes (they asked for none)"
                    : "playful: a light joke or a callback when it fits, never teasing a person";
            case ROUGH -> noJokes ? "plainly, without jokes (they asked for none)"
                    : "like a mate on a survival server: blunt and a bit cheeky, never teasing a person";
        };
        boolean rough = sounds == ROUGH && !noJokes && heard != null;
        boolean swears = rough && heard.swearing() == ListenerPreferences.Opt.ON;
        boolean dark = rough && heard.darkHumor() == ListenerPreferences.Opt.ON;
        boolean flirts = flirty && sounds.ordinal() >= PLAYFUL.ordinal() && !noJokes && heard != null && heard.flirting() == ListenerPreferences.Opt.ON;
        StringBuilder out = new StringBuilder("How you speak to them: ").append(line);
        out.append(swears ? "; casual swearing is fine (damn, hell, shit, wtf), never at them" : "; clean words only");
        if (dark) out.append("; dark jokes about dying in the game are fine");
        if (flirts) out.append("; light, harmless flirting is fine, never sexual");
        if (swears || dark || flirts) out.append("; never a slur, anything sexual, an insult or threat to them, or a joke about real-world harm");
        out.append('.');
        if (style != null && style != ChatStyle.PROPER) {
            out.append(" Type like a Minecraft player: lower case")
                    .append(style.punctuation() == ChatStyle.Punctuation.NONE ? ", no punctuation" : "")
                    .append(", shorthand like u, ur, rn, idk, tbh.");
        }
        return out.toString();
    }
}
