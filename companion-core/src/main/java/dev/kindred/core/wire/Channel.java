package dev.kindred.core.wire;

import dev.kindred.core.match.GameModes;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In plain terms: the named places a message can be sent on: to one person, to a team, to a faction, to everyone
 * living at a place, to one side of a match, to everyone playing a match, to everyone in earshot (or the whole server for a mode's own
 * announcements), or to the operators. Each has a limit on how many lines a minute go out on it and a window in which
 * the same message is not sent twice. Another mod's integration can name a channel of its own (a town's, a guild's)
 * without changing this code: it says who hears it by one of the audiences below.
 *
 * Technical: F-34's channels, with the design's defaults, as named values in a registry (the owner's mod-support rule,
 * 2026-11-09: channels are strings, not an enum). The eight built in keep their ids ({@code private}, {@code team},
 * ...), which is what the World Record and the Wire save. {@link #register} adds one: an id, its budget, the dedupe
 * window, the one visibility of a fact that belongs on it beside {@link Visibility#PUBLIC}, the {@link Audience} that
 * decides who is in a scope of it, and the world modes it is open in (none named: every mode). The budget counts
 * messages a minute per channel and scope; the dedupe window drops a message identical in kind and facts to one already
 * sent on the same scope within it. Channels are compared by identity: each id is registered once.
 */
public final class Channel {
    /** How a channel's audience is decided for a scope's key: the rule the Wire applies, fixed in core. */
    public enum Audience {
        /** The one player whose id is the key. */
        PRIVATE,
        /** The players on the team named by the key. */
        TEAM,
        /** The members of the faction named by the key. */
        FACTION,
        /** Everyone living at the place named by the key. */
        SETTLEMENT,
        /** One side of a match ({@code match:side}). */
        SIDE,
        /** Everyone playing the match named by the key. */
        MATCH,
        /** Everyone in earshot, or the whole server for a mode's announcements. */
        PUBLIC,
        /** The server's operators. */
        OPERATOR
    }

    private static final Map<String, Channel> REGISTRY = new LinkedHashMap<>();

    public static final Channel PRIVATE = builtIn("private", 6, 30_000, Visibility.SELF, Audience.PRIVATE);
    public static final Channel TEAM = builtIn("team", 6, 60_000, Visibility.TEAM, Audience.TEAM);
    public static final Channel FACTION = builtIn("faction", 10, 60_000, Visibility.FACTION, Audience.FACTION);
    public static final Channel SETTLEMENT = builtIn("settlement", 4, 120_000, Visibility.SETTLEMENT, Audience.SETTLEMENT);
    public static final Channel SIDE = builtIn("side", 6, 30_000, Visibility.SIDE, Audience.SIDE);
    public static final Channel MATCH = builtIn("match", 6, 30_000, Visibility.MATCH, Audience.MATCH);
    public static final Channel PUBLIC = builtIn("public", 3, 300_000, Visibility.PUBLIC, Audience.PUBLIC);
    public static final Channel OPERATOR = builtIn("operator", 20, 60_000, Visibility.OPERATOR, Audience.OPERATOR);

    private final String id;
    /** Messages a minute on one scope of this channel. */
    public final int perMinute;
    /** How long an identical message on the same scope is dropped, in milliseconds. */
    public final long dedupeMillis;
    /** The visibility of the facts this channel carries, beside public ones. */
    public final Visibility visibility;
    private final Audience audience;
    private final boolean builtIn;
    private final Set<GameModes.WorldMode> modes;

    private Channel(String id, int perMinute, long dedupeMillis, Visibility visibility, Audience audience, boolean builtIn, Set<GameModes.WorldMode> modes) {
        this.id = id;
        this.perMinute = perMinute;
        this.dedupeMillis = dedupeMillis;
        this.visibility = visibility;
        this.audience = audience;
        this.builtIn = builtIn;
        this.modes = modes;
    }

    private static Channel builtIn(String id, int perMinute, long dedupeMillis, Visibility visibility, Audience audience) {
        var c = new Channel(id, perMinute, dedupeMillis, visibility, audience, true, Set.of());
        REGISTRY.put(id, c);
        return c;
    }

    /**
     * Registers a channel an integration speaks on: {@code id} lower case, a letter then letters, digits, dots, dashes or
     * underscores, at most 48; at least a message a minute; a positive dedupe window; who hears it by {@code audience};
     * open in {@code modes} (none: every mode). Registering the same id again with the same settings gives back the one
     * channel; with other settings, or a built-in id, it is refused.
     */
    public static synchronized Channel register(String id, int perMinute, long dedupeMillis, Visibility visibility, Audience audience,
                                                Set<GameModes.WorldMode> modes) {
        String key = Objects.requireNonNull(id).strip().toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z][a-z0-9_.-]{0,47}")) throw new IllegalArgumentException("Bad channel id: " + id);
        if (perMinute < 1) throw new IllegalArgumentException("A channel needs at least a message a minute");
        if (dedupeMillis < 1) throw new IllegalArgumentException("A channel's dedupe window must be positive");
        Objects.requireNonNull(visibility);
        Objects.requireNonNull(audience);
        Set<GameModes.WorldMode> open = modes == null || modes.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(modes));
        Channel existing = REGISTRY.get(key);
        if (existing != null) {
            if (!existing.builtIn && existing.perMinute == perMinute && existing.dedupeMillis == dedupeMillis && existing.visibility == visibility
                    && existing.audience == audience && existing.modes.equals(open)) return existing;
            throw new IllegalArgumentException("Channel " + key + " is already registered" + (existing.builtIn ? " (built in)" : " with other settings"));
        }
        var c = new Channel(key, perMinute, dedupeMillis, visibility, audience, false, open);
        REGISTRY.put(key, c);
        return c;
    }

    /** Every channel known, the eight built in first, then those registered, in the order they came. */
    public static synchronized List<Channel> values() { return List.copyOf(new ArrayList<>(REGISTRY.values())); }

    /** The channel with this id ({@code faction}, {@code side}, an integration's own), any case; null for anything else. */
    public static synchronized Channel parse(String name) {
        return name == null ? null : REGISTRY.get(name.strip().toLowerCase(Locale.ROOT));
    }

    /** The id saved and sent: {@code team}, {@code faction}, an integration's own. */
    public String id() { return id; }

    /** The id in capitals, as the enum this replaces named the built-in channels. */
    public String name() { return id.toUpperCase(Locale.ROOT); }

    /** Who a scope of this channel reaches. */
    public Audience audience() { return audience; }

    /** Whether it is one of the eight built in. */
    public boolean builtIn() { return builtIn; }

    /** The world modes a registered channel is open in; empty for every mode (and for the built-in channels, whose modes {@link ModeChannels} gives). */
    public Set<GameModes.WorldMode> modes() { return modes; }

    @Override public String toString() { return name(); }
}
