package dev.kindred.core.wire;

import java.util.Locale;

/**
 * In plain terms: the named places a message can be sent on: to one person, to a team, to a faction, to everyone
 * living at a place, to one side of a match, to everyone playing a match, to everyone in earshot (or the whole server for a mode's own
 * announcements), or to the operators. Each has a limit on how many lines a minute go out on it and a window in which
 * the same message is not sent twice.
 *
 * Technical: F-34's channels, with the design's defaults. The budget counts messages a minute per channel and scope
 * (one faction's channel, one match side); the dedupe window drops a message identical in kind and facts to one
 * already sent on the same scope within it. {@link #visibility} is the one visibility of a fact that belongs on this
 * channel beside {@link Visibility#PUBLIC}.
 */
public enum Channel {
    PRIVATE(6, 30_000, Visibility.SELF),
    TEAM(6, 60_000, Visibility.TEAM),
    FACTION(10, 60_000, Visibility.FACTION),
    SETTLEMENT(4, 120_000, Visibility.SETTLEMENT),
    SIDE(6, 30_000, Visibility.SIDE),
    MATCH(6, 30_000, Visibility.MATCH),
    PUBLIC(3, 300_000, Visibility.PUBLIC),
    OPERATOR(20, 60_000, Visibility.OPERATOR);

    /** Messages a minute on one scope of this channel. */
    public final int perMinute;
    /** How long an identical message on the same scope is dropped, in milliseconds. */
    public final long dedupeMillis;
    /** The visibility of the facts this channel carries, beside public ones. */
    public final Visibility visibility;

    Channel(int perMinute, long dedupeMillis, Visibility visibility) {
        this.perMinute = perMinute;
        this.dedupeMillis = dedupeMillis;
        this.visibility = visibility;
    }

    public String id() { return name().toLowerCase(Locale.ROOT); }

    /** The channel named {@code faction}, {@code side} and so on, any case; null for anything else. */
    public static Channel parse(String name) {
        for (Channel c : values()) if (c.name().equalsIgnoreCase(name == null ? "" : name.strip())) return c;
        return null;
    }
}
