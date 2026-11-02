package dev.kindred.core.social;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: how an AI player feels about each person and player it knows, built only from what actually happened
 * between them: a gift it gave that was picked up, a gift it was given, an outing together, a blow it took from them. Each
 * thing is kept with when it happened and where the record came from, once (the same gift is not counted twice), and can
 * be struck out if it was wrong; what it feels is worked out again from what is left. Chat never counts: nothing said
 * makes a friend or an enemy, and a declined gift, a short command or a swear word is nothing at all.
 *
 * Technical: E-24a, pure. One record per player per world: up to {@value #MAX_PEERS} peers, the {@value #MAX_EVENTS}
 * newest events each. {@link #note} adds an attributed event under a key that makes a repeat a no-op; {@link #correct}
 * strikes one out, keeping who did and when; {@link #standing} is derived each time from the events not struck out, so a
 * correction changes it at once. Friendship never grants control, access or budgets: callers read it for tone and their
 * own discretionary choices only (E-25), and never for a reason to strike (AUTH-01, SOC-02).
 */
public final class Rapport {
    public static final int MAX_PEERS = 64;
    public static final int MAX_EVENTS = 50;
    /** Friendship is between these. */
    public static final int LOWEST = -100, HIGHEST = 100;

    /** What happened, and what it is worth to friendship. */
    public enum Kind {
        /** It gave them something, and they picked it up (E-22a). */
        GAVE(3),
        /** They gave it something, picked up by it. */
        RECEIVED(6),
        /** An outing together, done (E-27). */
        OUTING(5),
        /** A blow from them, outside any match they both joined, as the game recorded it (E-49). */
        HARMED(-12);

        public final int worth;

        Kind(int worth) { this.worth = worth; }
    }

    /** One thing that happened: its key (unique per peer), what, in words, when, from which record, and its correction. */
    public record Event(String key, UUID peer, Kind kind, String detail, long at, String source, UUID correctedBy, long correctedAt) {
        public Event {
            Objects.requireNonNull(key);
            Objects.requireNonNull(peer);
            Objects.requireNonNull(kind);
            detail = detail == null ? "" : detail;
            source = source == null ? "game" : source;
        }

        public boolean corrected() { return correctedBy != null; }
    }

    /** How it stands with one peer: friendship from the events not struck out, how many there are, and the last. */
    public record Standing(UUID peer, String name, int friendship, int counted, long lastAt) {
        /** In a word: the band a tone may read (E-25). */
        public String band() {
            if (friendship >= 30) return "friend";
            if (friendship >= 8) return "friendly";
            if (friendship <= -24) return "wary";
            if (friendship < 0) return "cool";
            return "neutral";
        }
    }

    private static final class Peer {
        String name;
        long touched;
        final ArrayDeque<Event> events = new ArrayDeque<>();
    }

    private final UUID self;
    private final Map<UUID, Peer> peers = new LinkedHashMap<>();

    public Rapport(UUID self) {
        this.self = Objects.requireNonNull(self);
    }

    public UUID self() { return self; }

    /**
     * Something that happened with {@code peer}, from the game's record ({@code source}): kept once under {@code key}
     * (the same key again is nothing new). The event kept, or empty when it was a repeat or about itself.
     */
    public Optional<Event> note(UUID peer, String name, Kind kind, String detail, String key, String source, long at) {
        Objects.requireNonNull(peer);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(key);
        if (peer.equals(self)) return Optional.empty();
        Peer p = peers.computeIfAbsent(peer, ignored -> new Peer());
        if (name != null && !name.isBlank()) p.name = name;
        for (Event e : p.events) if (e.key().equals(key)) return Optional.empty();
        Event event = new Event(key, peer, kind, detail, at, source, null, 0);
        p.events.addLast(event);
        while (p.events.size() > MAX_EVENTS) p.events.removeFirst();
        p.touched = Math.max(p.touched, at);
        if (peers.size() > MAX_PEERS) {
            UUID stalest = peers.entrySet().stream().filter(entry -> !entry.getKey().equals(peer))
                    .min(Comparator.comparingLong(entry -> entry.getValue().touched)).orElseThrow().getKey();
            peers.remove(stalest);
        }
        return Optional.of(event);
    }

    /** Strikes out the event with {@code key} about {@code peer}, as {@code by} said it was wrong; false if there is none. */
    public boolean correct(UUID peer, String key, UUID by, long at) {
        Peer p = peers.get(peer);
        if (p == null) return false;
        var kept = new ArrayDeque<Event>();
        boolean found = false;
        for (Event e : p.events) {
            if (!found && e.key().equals(key) && !e.corrected()) {
                kept.addLast(new Event(e.key(), e.peer(), e.kind(), e.detail(), e.at(), e.source(), Objects.requireNonNull(by), at));
                found = true;
            } else {
                kept.addLast(e);
            }
        }
        p.events.clear();
        p.events.addAll(kept);
        return found;
    }

    /** Everything kept about {@code peer} gone. */
    public boolean forget(UUID peer) {
        return peers.remove(peer) != null;
    }

    /** How it stands with {@code peer}: worked out again from the events not struck out. */
    public Standing standing(UUID peer) {
        Peer p = peers.get(peer);
        if (p == null) return new Standing(peer, "someone", 0, 0, 0);
        int sum = 0, counted = 0;
        long last = 0;
        for (Event e : p.events) {
            if (e.corrected()) continue;
            sum += e.kind().worth;
            counted++;
            last = Math.max(last, e.at());
        }
        return new Standing(peer, p.name == null ? "someone" : p.name, Math.max(LOWEST, Math.min(HIGHEST, sum)), counted, last);
    }

    /** Every peer's standing, the warmest first. */
    public List<Standing> standings() {
        var out = new ArrayList<Standing>();
        for (UUID peer : peers.keySet()) out.add(standing(peer));
        out.sort(Comparator.comparingInt(Standing::friendship).reversed());
        return out;
    }

    /** The events about {@code peer}, oldest first, struck-out ones included and marked. */
    public List<Event> events(UUID peer) {
        Peer p = peers.get(peer);
        return p == null ? List.of() : List.copyOf(p.events);
    }

    /** As kept on disk. */
    public record PeerRow(UUID peer, String name, long touched, List<Event> events) { }

    public record Snapshot(UUID self, List<PeerRow> peers) { }

    public Snapshot snapshot() {
        var rows = new ArrayList<PeerRow>();
        for (var entry : peers.entrySet()) rows.add(new PeerRow(entry.getKey(), entry.getValue().name, entry.getValue().touched, List.copyOf(entry.getValue().events)));
        return new Snapshot(self, List.copyOf(rows));
    }

    /** A record read back; anything unreadable in it is left out. */
    public static Rapport restore(UUID self, Snapshot snapshot) {
        var rapport = new Rapport(self);
        if (snapshot == null || snapshot.peers() == null) return rapport;
        for (PeerRow row : snapshot.peers()) {
            if (row == null || row.peer() == null || row.peer().equals(self) || row.events() == null) continue;
            Peer p = new Peer();
            p.name = row.name();
            p.touched = row.touched();
            for (Event e : row.events()) {
                if (e == null || e.key() == null || e.kind() == null || e.peer() == null) continue;
                p.events.addLast(e);
            }
            while (p.events.size() > MAX_EVENTS) p.events.removeFirst();
            rapport.peers.put(row.peer(), p);
            if (rapport.peers.size() >= MAX_PEERS) break;
        }
        return rapport;
    }
}
