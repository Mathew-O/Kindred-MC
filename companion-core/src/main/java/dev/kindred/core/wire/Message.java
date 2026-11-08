package dev.kindred.core.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: one message, written as a record before anything is put into words: what kind it is ("a capture",
 * "a need"), who sends it (a player, or the server itself), where it goes, the facts it carries with who may know
 * each, and the recorded events it rests on.
 *
 * Technical: F-34's {@code Message(kind, from, channel, data, event ids)}. {@code from} is null for the server.
 * {@code origin} says what the message was built from: a record (the sender's own state or the World Record's events),
 * a person's words, or an AI player's words; the last never enters the Wire (the echo rule). Event ids are F-31's,
 * staged as plain numbers until the Record is on main. Bounded: sixteen facts, sixteen event ids.
 */
public record Message(String kind, UUID from, Scope scope, Map<String, Fact> facts, List<Long> eventIds, Origin origin) {
    public static final int MAX_FACTS = 16;
    public static final int MAX_EVENTS = 16;

    /** What a message was built from. */
    public enum Origin {
        /** The sender's own state, or recorded events: what it knows to be so. */
        RECORD,
        /** A person's words, which receivers keep as a claim. */
        PERSON_WORDS,
        /** An AI player's words: never a message (the echo rule). */
        AI_WORDS
    }

    public Message {
        kind = Objects.requireNonNull(kind).strip().toLowerCase(Locale.ROOT);
        if (!kind.matches("[a-z][a-z0-9_.-]{0,47}")) throw new IllegalArgumentException("Bad message kind: " + kind);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(origin);
        var bounded = new LinkedHashMap<String, Fact>();
        if (facts != null) for (var e : facts.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            bounded.put(e.getKey().strip(), e.getValue());
            if (bounded.size() == MAX_FACTS) break;
        }
        facts = java.util.Collections.unmodifiableMap(bounded);
        var events = new ArrayList<Long>();
        if (eventIds != null) for (Long id : eventIds) {
            if (id == null || id <= 0) continue;
            events.add(id);
            if (events.size() == MAX_EVENTS) break;
        }
        eventIds = List.copyOf(events);
    }

    /** A message built from the sender's record. */
    public static Message of(String kind, UUID from, Scope scope, Map<String, Fact> facts, List<Long> eventIds) {
        return new Message(kind, from, scope, facts, eventIds, Origin.RECORD);
    }

    /** Whether the server sends it. */
    public boolean fromServer() { return from == null; }

    /** The message's identity for dedupe: its kind and facts, in order. */
    String sameness() {
        var out = new StringBuilder(kind);
        facts.forEach((k, v) -> out.append('|').append(k).append('=').append(v.value()));
        return out.toString();
    }
}
