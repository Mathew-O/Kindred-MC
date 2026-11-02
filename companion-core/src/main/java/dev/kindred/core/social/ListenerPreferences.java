package dev.kindred.core.social;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: how a person has asked the AI players to talk to them: fewer or more lines they did not ask for, no
 * teasing, no jokes. Only that person sets their own, and can delete them. When a line is heard by several people, the
 * strictest of everyone's settings applies to it.
 *
 * Technical: E-19a. One record per person (never per AI player), kept in the metadata store and deletable. The defaults
 * change nothing: the usual number of lines, teasing and jokes as the speaker's preset allows.
 */
public record ListenerPreferences(UUID person, Unasked unasked, boolean noTeasing, boolean noJokes, long revision) {
    /** How many lines the person wants that they did not ask for. */
    public enum Unasked {
        FEWER, USUAL, MORE;

        /** The setting named {@code fewer}, {@code usual} or {@code more}, any case; null for anything else. */
        public static Unasked parse(String name) {
            for (Unasked u : values()) if (u.name().equalsIgnoreCase(name == null ? "" : name.strip())) return u;
            return null;
        }
    }

    public ListenerPreferences {
        Objects.requireNonNull(unasked);
        if (revision < 0) throw new IllegalArgumentException("Negative preference revision");
    }

    /** Nothing asked for: the usual lines, teasing and jokes as the speaker's preset allows. */
    public static ListenerPreferences defaults(UUID person) {
        return new ListenerPreferences(person, Unasked.USUAL, false, false, 0);
    }

    /** Whether these are the defaults, so that nothing need be kept for the person. */
    public boolean isDefault() { return unasked == Unasked.USUAL && !noTeasing && !noJokes; }

    public ListenerPreferences withUnasked(Unasked next) {
        return new ListenerPreferences(person, Objects.requireNonNull(next), noTeasing, noJokes, Math.incrementExact(revision));
    }

    public ListenerPreferences withNoTeasing(boolean next) {
        return new ListenerPreferences(person, unasked, next, noJokes, Math.incrementExact(revision));
    }

    public ListenerPreferences withNoJokes(boolean next) {
        return new ListenerPreferences(person, unasked, noTeasing, next, Math.incrementExact(revision));
    }

    /**
     * The strictest of everyone's settings who will hear a line: no jokes if anyone asked for none, no teasing likewise,
     * and the fewest lines anyone asked for. Nobody listed is the defaults.
     */
    public static ListenerPreferences strictest(Collection<ListenerPreferences> heard) {
        Unasked unasked = Unasked.USUAL;
        boolean noTeasing = false, noJokes = false;
        boolean any = false;
        for (ListenerPreferences p : heard) {
            if (p == null) continue;
            unasked = !any || p.unasked.ordinal() < unasked.ordinal() ? p.unasked : unasked;
            any = true;
            noTeasing |= p.noTeasing;
            noJokes |= p.noJokes;
        }
        return new ListenerPreferences(null, any ? unasked : Unasked.USUAL, noTeasing, noJokes, 0);
    }

    /** The pause before this person is spoken to unasked again, from the usual pause: twice it for fewer, half for more. */
    public long spacing(long usualMillis) {
        return switch (unasked) {
            case FEWER -> usualMillis * 2;
            case USUAL -> usualMillis;
            case MORE -> usualMillis / 2;
        };
    }
}
