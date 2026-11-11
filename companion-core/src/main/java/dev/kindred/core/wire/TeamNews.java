package dev.kindred.core.wire;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * In plain terms: what an AI player tells its team about the team's own storage, as facts on the team's channel (or in
 * a manhunt its side's): what it put into a shared store or took out of it, and what it found gone from one. Its
 * teammates know it as told, with where; nobody outside the team ever gets it. The words a person on the team hears
 * stay the player's own line in chat; this is what the Wire carries beside them.
 *
 * Technical: F-34c's team-talk path. Three kinds, each a {@link Message} of the scope's visibility: {@link #STORED}
 * and {@link #TOOK} ({@code stored} / {@code took}: the items and the place, as the web's verbs of the same name;
 * {@code items}, {@code place}), {@link #MISSING} ({@code missing}; {@code items}, {@code place}). Built only for a
 * team's or a side's scope.
 */
public final class TeamNews {
    public static final String STORED = "team.stored";
    public static final String TOOK = "team.took";
    public static final String MISSING = "team.missing";

    private TeamNews() { }

    /** What {@code from} put into ({@code put}) or took out of a store its team shares, as {@code items} at {@code place}. */
    public static Message stored(UUID from, Scope scope, boolean put, String items, String place) {
        return of(put ? STORED : TOOK, put ? "stored" : "took", from, scope, items, place);
    }

    /** What {@code from} found gone from a store its team shares. */
    public static Message missing(UUID from, Scope scope, String items, String place) {
        return of(MISSING, "missing", from, scope, items, place);
    }

    private static Message of(String kind, String verb, UUID from, Scope scope, String items, String place) {
        if (scope.channel() != Channel.TEAM && scope.channel() != Channel.SIDE) throw new IllegalArgumentException("A team's or a side's news only");
        if (items == null || items.isBlank() || place == null || place.isBlank()) throw new IllegalArgumentException("What and where");
        Visibility v = scope.channel().visibility;
        var facts = new LinkedHashMap<String, Fact>();
        facts.put(verb, Fact.of(items.strip() + " at " + place.strip(), v));
        facts.put("items", Fact.of(items.strip(), v));
        facts.put("place", Fact.of(place.strip(), v));
        return Message.of(kind, from, scope, facts, List.of());
    }
}
