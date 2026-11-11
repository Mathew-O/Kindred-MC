package dev.kindred.core.record;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * In plain terms: one thing that happened, kept for good in the World Record: a death, a match's result, a claim, a
 * sale. It is never changed or taken back; a correction is another event.
 *
 * Technical: One row of the append-only {@code world_events} table (F-31). {@code id} is zero for an event not yet
 * written and the Record's own sequence once it is. {@code tick} is the server tick, or -1 where the writer has no game
 * clock (a journal line, an action-ledger transition). {@code howKnown} says whether it is the game's own record, a
 * structured message, or only words.
 */
public record WorldEvent(long id, long tick, long realTimeMs, String world, String kind, UUID actor, String subject, String place,
                         Map<String, String> data, HowKnown howKnown) {
    public static final int MAX_TEXT = 256;
    public static final int MAX_DATA = 16_384;
    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_.]{0,63}");

    /** Whether {@code kind} is spelled as an event kind may be: lower case, digits, '_' and '.', at most 64 characters. */
    public static boolean validKind(String kind) { return kind != null && KIND.matcher(kind).matches(); }

    /** How the Record knows it: the game's own record, a structured message, or someone's words. */
    public enum HowKnown {
        RECORDED, TOLD, CLAIMED;

        public String code() { return name().toLowerCase(java.util.Locale.ROOT); }

        public static HowKnown parse(String code) { return valueOf(code.toUpperCase(java.util.Locale.ROOT)); }
    }

    public WorldEvent {
        if (id < 0) throw new IllegalArgumentException("An event id is never negative");
        if (tick < -1) throw new IllegalArgumentException("A tick is -1 (no game clock) or more");
        if (realTimeMs < 0) throw new IllegalArgumentException("A real time is never negative");
        world = text(world, "world");
        Objects.requireNonNull(kind, "kind");
        if (!KIND.matcher(kind).matches()) throw new IllegalArgumentException("Invalid event kind " + kind);
        subject = text(subject, "subject");
        place = text(place, "place");
        Objects.requireNonNull(howKnown, "howKnown");
        var copy = new LinkedHashMap<String, String>();
        for (var entry : Objects.requireNonNull(data, "data").entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) throw new IllegalArgumentException("Invalid event data");
            copy.put(entry.getKey(), entry.getValue());
        }
        data = Collections.unmodifiableMap(copy);
        if (EventData.encode(data).length() > MAX_DATA) throw new IllegalArgumentException("Event data too long");
    }

    /** An event not yet written, recorded by the game itself. */
    public static WorldEvent recorded(long tick, long realTimeMs, String world, String kind, UUID actor, String subject, String place,
                                      Map<String, String> data) {
        return new WorldEvent(0, tick, realTimeMs, world, kind, actor, subject, place, data, HowKnown.RECORDED);
    }

    /** The same event as the Record keeps it, under its sequence. */
    public WorldEvent withId(long id) {
        if (id < 1) throw new IllegalArgumentException("A written event's id is positive");
        return new WorldEvent(id, tick, realTimeMs, world, kind, actor, subject, place, data, howKnown);
    }

    /** One value of its data, or {@code fallback}. */
    public String get(String key, String fallback) { return data.getOrDefault(key, fallback); }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > MAX_TEXT) throw new IllegalArgumentException(name + " too long");
        return value;
    }
}
