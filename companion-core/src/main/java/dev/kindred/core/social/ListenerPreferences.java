package dev.kindred.core.social;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: how a person has asked the AI players to talk to them: fewer or more lines they did not ask for, no
 * teasing, no jokes; and whether they want the rough stuff: swearing, dark jokes, flirting. Only that person sets their
 * own, and can delete them. When a line is heard by several people, the strictest of everyone's settings applies to it.
 * Swearing and dark jokes are off unless the person says yes, or the server's operator said yes for everyone and the
 * person has not said no; flirting is only ever the person's own yes.
 *
 * Technical: E-19a, with E-18b's three opt-ins. One record per person (never per AI player), kept in the metadata store and
 * deletable. The defaults change nothing: the usual number of lines, teasing and jokes as the speaker's preset allows, and
 * the rough stuff left to the server's default ({@link Opt#DEFAULT}) until {@link #resolved} reads it.
 */
public record ListenerPreferences(UUID person, Unasked unasked, boolean noTeasing, boolean noJokes, Opt swearing, Opt darkHumor,
                                  Opt flirting, long revision) {
    /** How many lines the person wants that they did not ask for. */
    public enum Unasked {
        FEWER, USUAL, MORE;

        /** The setting named {@code fewer}, {@code usual} or {@code more}, any case; null for anything else. */
        public static Unasked parse(String name) {
            for (Unasked u : values()) if (u.name().equalsIgnoreCase(name == null ? "" : name.strip())) return u;
            return null;
        }
    }

    /** A person's yes or no to one kind of rough talk, or nothing said, which the server's default decides. */
    public enum Opt {
        DEFAULT, ON, OFF;

        /** {@code on}, {@code off} or {@code default}, any case; null for anything else. */
        public static Opt parse(String name) {
            if (name == null) return null;
            return switch (name.strip().toLowerCase(Locale.ROOT)) {
                case "on", "yes", "true" -> ON;
                case "off", "no", "false" -> OFF;
                case "default", "auto" -> DEFAULT;
                default -> null;
            };
        }

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public ListenerPreferences {
        Objects.requireNonNull(unasked);
        swearing = swearing == null ? Opt.DEFAULT : swearing;
        darkHumor = darkHumor == null ? Opt.DEFAULT : darkHumor;
        flirting = flirting == null ? Opt.DEFAULT : flirting;
        if (revision < 0) throw new IllegalArgumentException("Negative preference revision");
    }

    /** E-19a's settings, with the rough stuff left to the server's default. */
    public ListenerPreferences(UUID person, Unasked unasked, boolean noTeasing, boolean noJokes, long revision) {
        this(person, unasked, noTeasing, noJokes, Opt.DEFAULT, Opt.DEFAULT, Opt.DEFAULT, revision);
    }

    /** Nothing asked for: the usual lines, teasing and jokes as the speaker's preset allows, the rough stuff by default. */
    public static ListenerPreferences defaults(UUID person) {
        return new ListenerPreferences(person, Unasked.USUAL, false, false, 0);
    }

    /** Whether these are the defaults, so that nothing need be kept for the person. */
    public boolean isDefault() {
        return unasked == Unasked.USUAL && !noTeasing && !noJokes && swearing == Opt.DEFAULT && darkHumor == Opt.DEFAULT && flirting == Opt.DEFAULT;
    }

    public ListenerPreferences withUnasked(Unasked next) {
        return new ListenerPreferences(person, Objects.requireNonNull(next), noTeasing, noJokes, swearing, darkHumor, flirting, Math.incrementExact(revision));
    }

    public ListenerPreferences withNoTeasing(boolean next) {
        return new ListenerPreferences(person, unasked, next, noJokes, swearing, darkHumor, flirting, Math.incrementExact(revision));
    }

    public ListenerPreferences withNoJokes(boolean next) {
        return new ListenerPreferences(person, unasked, noTeasing, next, swearing, darkHumor, flirting, Math.incrementExact(revision));
    }

    public ListenerPreferences withSwearing(Opt next) {
        return new ListenerPreferences(person, unasked, noTeasing, noJokes, Objects.requireNonNull(next), darkHumor, flirting, Math.incrementExact(revision));
    }

    public ListenerPreferences withDarkHumor(Opt next) {
        return new ListenerPreferences(person, unasked, noTeasing, noJokes, swearing, Objects.requireNonNull(next), flirting, Math.incrementExact(revision));
    }

    public ListenerPreferences withFlirting(Opt next) {
        return new ListenerPreferences(person, unasked, noTeasing, noJokes, swearing, darkHumor, Objects.requireNonNull(next), Math.incrementExact(revision));
    }

    /**
     * These settings with the server's default read in: swearing and dark jokes on where the person said nothing and the
     * operator said yes for everyone ({@code roughAudience}), off where neither did; flirting off unless the person said yes.
     * Whatever is resolved is ON or OFF, never DEFAULT.
     */
    public ListenerPreferences resolved(boolean roughAudience) {
        Opt byServer = roughAudience ? Opt.ON : Opt.OFF;
        return new ListenerPreferences(person, unasked, noTeasing, noJokes,
                swearing == Opt.DEFAULT ? byServer : swearing,
                darkHumor == Opt.DEFAULT ? byServer : darkHumor,
                flirting == Opt.ON ? Opt.ON : Opt.OFF, revision);
    }

    /** Whether swearing may be heard: said yes to (once {@link #resolved}). */
    public boolean swears() { return swearing == Opt.ON && !noJokes; }

    /** Whether a dark joke may be heard. */
    public boolean dark() { return darkHumor == Opt.ON && !noJokes; }

    /** Whether flirting may be heard. */
    public boolean flirts() { return flirting == Opt.ON && !noJokes; }

    /**
     * The strictest of everyone's settings who will hear a line: no jokes if anyone asked for none, no teasing likewise,
     * the fewest lines anyone asked for, and each kind of rough talk only if every one of them said yes to it. Nobody
     * listed is the defaults.
     */
    public static ListenerPreferences strictest(Collection<ListenerPreferences> heard) {
        Unasked unasked = Unasked.USUAL;
        boolean noTeasing = false, noJokes = false;
        Opt swearing = null, dark = null, flirting = null;
        boolean any = false;
        for (ListenerPreferences p : heard) {
            if (p == null) continue;
            unasked = !any || p.unasked.ordinal() < unasked.ordinal() ? p.unasked : unasked;
            any = true;
            noTeasing |= p.noTeasing;
            noJokes |= p.noJokes;
            swearing = stricter(swearing, p.swearing);
            dark = stricter(dark, p.darkHumor);
            flirting = stricter(flirting, p.flirting);
        }
        return new ListenerPreferences(null, any ? unasked : Unasked.USUAL, noTeasing, noJokes, swearing, dark, flirting, 0);
    }

    /** No beats nothing said, which beats yes. */
    private static Opt stricter(Opt so, Opt next) {
        if (so == null) return next;
        if (so == Opt.OFF || next == Opt.OFF) return Opt.OFF;
        if (so == Opt.DEFAULT || next == Opt.DEFAULT) return Opt.DEFAULT;
        return Opt.ON;
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
