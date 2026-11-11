package dev.kindred.core.social;

import dev.kindred.core.VanillaIds;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In plain terms: what one AI player knows of the others it has met in one world, people and AI players alike: when and
 * where it last saw them, what its teammates told it through the team (what they are doing, need and could spare), what
 * it saw happen between them, what was handed over each way, and what people said of themselves. What it saw counts most,
 * then what a teammate's own record says, then what was said, which is only ever a claim: it holds for a game day, is
 * never told as a fact, and can be taken back by whoever said it.
 *
 * Technical: E-50. Pure and deterministic, one per player per world; the adapter feeds it and keeps it. Three sources in
 * trust order ({@link Source}): the game's own record (sightings, events, hand-overs), structured updates between AI
 * players sent through the runtime ({@link PeerUpdate}, never chat), and spoken claims. For each topic the most trusted
 * current note stands; a claim is shown beside it only as a claim, and only while nothing more trusted on its topic is
 * newer. A person's place is kept only while it is in sight; a teammate's only while its updates carry it. A claim
 * expires a game day after it is made; one confirmed by a sighting is retired. A correction ("forget that", "that's
 * wrong") marks a claim with who corrected it, when and how, keeping who said it and when; corrected claims are never
 * told. Bounded: {@value #MAX_PEERS} peers (the least lately met dropped), {@value #MAX_EPISODES} episodes each, eight
 * claims each. Nothing said creates anything here but a claim.
 */
public final class PeerLedger {
    public static final int MAX_PEERS = 64;
    public static final int MAX_EPISODES = 20;
    public static final int MAX_CLAIMS = 8;
    /** A game day, in ticks: how long a claim holds. */
    public static final long GAME_DAY = 24_000;

    /** A person, or another AI player. */
    public enum Kind { PERSON, AI_PLAYER }

    /** Where a note came from, the most trusted first. */
    public enum Source { GAME, TEAM, SAID }

    /** What a note is about. */
    public enum Topic {
        DOING("doing"), NEEDS("needs"), SPARE("could spare");

        private final String words;

        Topic(String words) { this.words = words; }

        public String words() { return words; }
    }

    /** How a claim was taken back. */
    public enum Correction { FORGOTTEN, WRONG }

    /** A place in a dimension, by its id (minecraft:the_nether). */
    public record Place(String dimension, int x, int y, int z) {
        public Place {
            Objects.requireNonNull(dimension);
        }

        /** The dimension in words: the Overworld, the Nether, the End, or its id. */
        public String world() { return worldWords(dimension); }

        public String words() { return x + ", " + y + ", " + z + " in " + world(); }
    }

    /**
     * A note on one topic: its words, its source, who gave it (the peer itself, or whoever spoke), when, and until when it
     * holds ({@link Long#MAX_VALUE} for always). A claim may name the dimension it says they are going to; a sighting there
     * after it confirms it. A corrected claim keeps all of this and says who corrected it, when and how.
     */
    public record Note(Topic topic, Source source, String text, UUID by, long at, long until, String dimension,
                       Correction correction, UUID correctedBy, long correctedAt, long confirmedAt) {
        public Note {
            Objects.requireNonNull(topic);
            Objects.requireNonNull(source);
            Objects.requireNonNull(text);
        }

        public boolean corrected() { return correction != null; }

        public boolean confirmed() { return confirmedAt > 0; }

        /** Whether it is to be used at {@code now}: not expired, corrected or confirmed. */
        public boolean current(long now) { return !corrected() && !confirmed() && now < until; }

        Note corrected(Correction how, UUID who, long when) {
            return new Note(topic, source, text, by, at, until, dimension, how, who, when, confirmedAt);
        }

        Note confirmedAt(long when) {
            return new Note(topic, source, text, by, at, until, dimension, correction, correctedBy, correctedAt, when);
        }
    }

    /** Something the game saw happen between them, and when. */
    public record Episode(String text, long at) {
        public Episode {
            Objects.requireNonNull(text);
        }
    }

    /**
     * One line of what the player knows of a peer, for the voice or a reply, with who may be told it: {@link Reach#ANYONE}
     * (what anyone beside it might see or know of it), {@link Reach#SIDE} (its controllers and teammates: the team's own
     * business, what was said to it), {@link Reach#CONTROLLER} (a place).
     */
    public record Told(UUID about, String name, String text, Reach reach, boolean claim) {
        public Told {
            Objects.requireNonNull(about);
            Objects.requireNonNull(text);
            Objects.requireNonNull(reach);
        }
    }

    /** Who may be told a line. */
    public enum Reach { ANYONE, SIDE, CONTROLLER }

    /** What is known of one peer, as kept. */
    private static final class Peer {
        final UUID id;
        String name;
        Kind kind;
        long firstMet, lastSeen = -1, touched;
        String lastWorld;
        Place place;
        final Map<Topic, Map<Source, Note>> notes = new TreeMap<>();
        final List<Note> claims = new ArrayList<>();
        final ArrayDeque<Episode> episodes = new ArrayDeque<>();
        final Map<String, Integer> given = new TreeMap<>(), received = new TreeMap<>();

        Peer(UUID id, String name, Kind kind, long now) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.firstMet = now;
            this.touched = now;
        }
    }

    private final UUID self;
    private final Map<UUID, Peer> peers = new LinkedHashMap<>();

    /** An empty ledger for the player {@code self}. */
    public PeerLedger(UUID self) {
        this.self = Objects.requireNonNull(self);
    }

    public UUID self() { return self; }

    public int size() { return peers.size(); }

    public boolean knows(UUID peer) { return peers.containsKey(peer); }

    private Peer peer(UUID id, String name, Kind kind, long now) {
        Objects.requireNonNull(id);
        if (id.equals(self)) throw new IllegalArgumentException("A player is not its own peer");
        Peer peer = peers.get(id);
        if (peer == null) {
            peer = new Peer(id, name == null ? "someone" : name, kind == null ? Kind.PERSON : kind, now);
            peers.put(id, peer);
            if (peers.size() > MAX_PEERS) {
                Peer stalest = peers.values().stream().filter(p -> !p.id.equals(id)).min(Comparator.comparingLong(p -> p.touched)).orElseThrow();
                peers.remove(stalest.id);
            }
        } else {
            if (name != null && !name.isBlank()) peer.name = name;
            if (kind != null) peer.kind = kind;
        }
        peer.touched = Math.max(peer.touched, now);
        return peer;
    }

    // ----- The game's own record -----

    /**
     * Who it can see now (the game's record): each is met or seen again, a person's place kept while in sight, a teammate's
     * place left to its updates. A person not in {@code inSight} has no place kept any more. A claim of going to a
     * dimension is confirmed by a sighting there after it.
     */
    public void sighting(Collection<Sighting> inSight, long now) {
        var seen = new java.util.HashSet<UUID>();
        for (Sighting s : inSight) {
            if (s.id().equals(self)) continue;
            Peer peer = peer(s.id(), s.name(), s.kind(), now);
            seen.add(s.id());
            peer.lastSeen = now;
            if (s.place() != null) {
                peer.lastWorld = s.place().dimension();
                if (peer.kind == Kind.PERSON) peer.place = s.place();
                for (int i = 0; i < peer.claims.size(); i++) {
                    Note claim = peer.claims.get(i);
                    if (claim.current(now) && claim.dimension() != null && claim.dimension().equals(s.place().dimension()) && claim.at() < now) {
                        peer.claims.set(i, claim.confirmedAt(now));
                        put(peer, new Note(Topic.DOING, Source.GAME, "seen in " + worldWords(claim.dimension()), s.id(), now, Long.MAX_VALUE,
                                null, null, null, 0, 0));
                    }
                }
            }
        }
        for (Peer peer : peers.values()) if (peer.kind == Kind.PERSON && !seen.contains(peer.id)) peer.place = null;
    }

    /** One peer in sight: who, what it is, and where (null when only its presence is known). */
    public record Sighting(UUID id, String name, Kind kind, Place place) {
        public Sighting {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
        }
    }

    /** Something the game saw happen between them ("struck me", "pulled me out of the pit"), the oldest dropped past twenty. */
    public void episode(UUID id, String name, Kind kind, String text, long now) {
        Peer peer = peer(id, name, kind, now);
        peer.episodes.addLast(new Episode(clean(text), now));
        while (peer.episodes.size() > MAX_EPISODES) peer.episodes.removeFirst();
    }

    /** Items it handed them, as the game recorded the hand-over. */
    public void gave(UUID id, String name, Kind kind, String item, int count, long now) {
        if (count <= 0) return;
        peer(id, name, kind, now).given.merge(clean(item), count, Integer::sum);
    }

    /** Items they handed it, as the game recorded the pickup. */
    public void received(UUID id, String name, Kind kind, String item, int count, long now) {
        if (count <= 0) return;
        peer(id, name, kind, now).received.merge(clean(item), count, Integer::sum);
    }

    // ----- Structured updates between AI players -----

    /**
     * A teammate's own update, sent through the runtime (never chat): what it is doing, needs and could spare, and its place
     * when the team shares it (null when not: any place it had is dropped).
     */
    public void update(PeerUpdate update, long now) {
        Peer peer = peer(update.from(), update.name(), Kind.AI_PLAYER, now);
        put(peer, new Note(Topic.DOING, Source.TEAM, update.doing(), update.from(), now, Long.MAX_VALUE, null, null, null, 0, 0));
        put(peer, new Note(Topic.NEEDS, Source.TEAM, String.join(", ", update.needs()), update.from(), now, Long.MAX_VALUE, null, null, null, 0, 0));
        put(peer, new Note(Topic.SPARE, Source.TEAM, String.join(", ", update.spare()), update.from(), now, Long.MAX_VALUE, null, null, null, 0, 0));
        peer.place = update.place();
        if (update.place() != null) peer.lastWorld = update.place().dimension();
    }

    private static void put(Peer peer, Note note) {
        peer.notes.computeIfAbsent(note.topic(), key -> new TreeMap<>()).put(note.source(), note);
    }

    // ----- Spoken claims -----

    /**
     * Something a person said of themselves ({@code by} is who said it and who it is about): a claim, held a game day, never
     * a fact. {@code dimension} is where it says they are going, if anywhere: a sighting there confirms it.
     */
    public Note claim(UUID by, String name, Topic topic, String text, String dimension, long now) {
        Peer peer = peer(by, name, null, now);
        var note = new Note(topic, Source.SAID, clean(text), by, now, now + GAME_DAY, dimension, null, null, 0, 0);
        peer.claims.add(note);
        while (peer.claims.size() > MAX_CLAIMS) peer.claims.removeFirst();
        return note;
    }

    /**
     * Whoever said something takes it back: their latest current claim, or the latest whose words contain {@code about}
     * when given. The claim is kept, marked with who corrected it, when and how; it is never told again. Empty when they
     * have said nothing current to take back. Only the one who said it may correct it.
     */
    public Optional<Note> correct(UUID by, String about, Correction how, long now) {
        Peer peer = peers.get(by);
        if (peer == null) return Optional.empty();
        String word = about == null ? null : about.strip().toLowerCase(Locale.ROOT);
        for (int i = peer.claims.size() - 1; i >= 0; i--) {
            Note claim = peer.claims.get(i);
            if (!claim.current(now) || !by.equals(claim.by())) continue;
            if (word != null && !word.isEmpty() && !claim.text().toLowerCase(Locale.ROOT).contains(word)) continue;
            Note corrected = claim.corrected(how, by, now);
            peer.claims.set(i, corrected);
            peer.touched = Math.max(peer.touched, now);
            return Optional.of(corrected);
        }
        return Optional.empty();
    }

    /** An operator's {@code memory forget}: everything known of this peer removed. */
    public boolean forget(UUID peer) {
        return peers.remove(peer) != null;
    }

    // ----- Reading it -----

    /** The note that stands on {@code topic} for this peer at {@code now}: the most trusted current one, or empty. */
    public Optional<Note> best(UUID id, Topic topic, long now) {
        Peer peer = peers.get(id);
        if (peer == null) return Optional.empty();
        var byTopic = peer.notes.get(topic);
        if (byTopic != null) {
            for (Source source : Source.values()) {
                Note note = byTopic.get(source);
                if (note != null && note.current(now) && !note.text().isBlank()) return Optional.of(note);
            }
        }
        return Optional.empty();
    }

    /** Its claims on {@code topic} that still hold and that nothing more trusted on the topic is newer than; newest first. */
    public List<Note> claims(UUID id, Topic topic, long now) {
        Peer peer = peers.get(id);
        if (peer == null) return List.of();
        long trusted = best(id, topic, now).map(Note::at).orElse(Long.MIN_VALUE);
        var out = new ArrayList<Note>();
        for (int i = peer.claims.size() - 1; i >= 0; i--) {
            Note claim = peer.claims.get(i);
            if (claim.topic() == topic && claim.current(now) && claim.at() > trusted) out.add(claim);
        }
        return List.copyOf(out);
    }

    /** Every claim kept for this peer, corrected and confirmed ones included, oldest first: their provenance. */
    public List<Note> claimsKept(UUID id) {
        Peer peer = peers.get(id);
        return peer == null ? List.of() : List.copyOf(peer.claims);
    }

    /** Where it knows this peer to be now: a person in sight, or a teammate sharing its place; empty otherwise. */
    public Optional<Place> place(UUID id) {
        Peer peer = peers.get(id);
        return peer == null ? Optional.empty() : Optional.ofNullable(peer.place);
    }

    /** The peer known by this name (any case), if one is. */
    public Optional<UUID> named(String name) {
        if (name == null) return Optional.empty();
        String wanted = name.strip();
        return peers.values().stream().filter(p -> p.name.equalsIgnoreCase(wanted)).max(Comparator.comparingLong(p -> p.touched)).map(p -> p.id);
    }

    /** When it last saw this peer itself, and in which dimension (null when unknown); empty if it never has. */
    public Optional<Seen> seen(UUID id) {
        Peer peer = peers.get(id);
        return peer == null || peer.lastSeen < 0 ? Optional.empty() : Optional.of(new Seen(peer.lastSeen, peer.lastWorld));
    }

    /** A sighting kept: when, and the dimension. */
    public record Seen(long at, String world) { }

    public Optional<String> name(UUID id) {
        Peer peer = peers.get(id);
        return peer == null ? Optional.empty() : Optional.of(peer.name);
    }

    public Optional<Kind> kind(UUID id) {
        Peer peer = peers.get(id);
        return peer == null ? Optional.empty() : Optional.of(peer.kind);
    }

    public List<Episode> episodes(UUID id) {
        Peer peer = peers.get(id);
        return peer == null ? List.of() : List.copyOf(peer.episodes);
    }

    public Map<String, Integer> given(UUID id) {
        Peer peer = peers.get(id);
        return peer == null ? Map.of() : Map.copyOf(peer.given);
    }

    public Map<String, Integer> received(UUID id) {
        Peer peer = peers.get(id);
        return peer == null ? Map.of() : Map.copyOf(peer.received);
    }

    /**
     * What it knows of the others as lines for the voice or a reply, each with who may be told it; the {@code limit} most
     * lately met. Facts are written as facts and claims as what was said and not seen; nothing corrected or expired is in
     * it. A place is a line of its own, for controllers alone.
     */
    public List<Told> told(long now, int limit) {
        var out = new ArrayList<Told>();
        var recent = peers.values().stream().sorted(Comparator.comparingLong((Peer p) -> p.touched).reversed()).limit(Math.max(0, limit)).toList();
        for (Peer peer : recent) {
            boolean teammate = peer.kind == Kind.AI_PLAYER;
            String who = peer.name;
            if (peer.lastSeen >= 0) {
                out.add(new Told(peer.id, who, who + ": last seen " + ago(now - peer.lastSeen) + (peer.lastWorld == null ? "" : " in " + worldWords(peer.lastWorld)),
                        Reach.ANYONE, false));
            }
            if (peer.place != null) out.add(new Told(peer.id, who, who + " is at " + peer.place.words(), Reach.CONTROLLER, false));
            for (Topic topic : Topic.values()) {
                best(peer.id, topic, now).ifPresent(note -> out.add(new Told(peer.id, who, fact(who, note), note.source() == Source.GAME ? Reach.ANYONE : Reach.SIDE, false)));
                for (Note claim : claims(peer.id, topic, now)) {
                    out.add(new Told(peer.id, who, who + " said, " + ago(now - claim.at()) + ", that they " + claimWords(claim)
                            + " (said, not seen)", Reach.SIDE, true));
                }
            }
            if (!peer.given.isEmpty()) out.add(new Told(peer.id, who, "You have handed " + who + " " + counts(peer.given), teammate ? Reach.SIDE : Reach.ANYONE, false));
            if (!peer.received.isEmpty()) out.add(new Told(peer.id, who, who + " has handed you " + counts(peer.received), teammate ? Reach.SIDE : Reach.ANYONE, false));
            var last = peer.episodes.peekLast();
            if (last != null) out.add(new Told(peer.id, who, "With " + who + ", " + ago(now - last.at()) + ": " + last.text(), Reach.SIDE, false));
        }
        return List.copyOf(out);
    }

    private static String fact(String who, Note note) {
        return switch (note.topic()) {
            case DOING -> who + " is " + note.text() + (note.source() == Source.TEAM ? " (from " + who + "'s own update)" : "");
            case NEEDS -> who + " needs " + note.text() + (note.source() == Source.TEAM ? " (from " + who + "'s own update)" : "");
            case SPARE -> who + " could spare " + note.text() + (note.source() == Source.TEAM ? " (from " + who + "'s own update)" : "");
        };
    }

    static String claimWords(Note claim) {
        return switch (claim.topic()) {
            case DOING -> "were " + claim.text();
            case NEEDS -> "needed " + claim.text();
            case SPARE -> "could spare " + claim.text();
        };
    }

    private static String counts(Map<String, Integer> items) {
        var parts = new ArrayList<String>();
        items.forEach((item, count) -> parts.add(count + " " + item));
        return String.join(", ", parts);
    }

    /** A span of game ticks in words: "just now", "4 minutes ago", "2 hours ago", "3 days ago". */
    public static String ago(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        if (seconds < 60) return "just now";
        if (seconds < 3_600) return (seconds / 60) + (seconds / 60 == 1 ? " minute ago" : " minutes ago");
        if (seconds < 86_400) return (seconds / 3_600) + (seconds / 3_600 == 1 ? " hour ago" : " hours ago");
        return (seconds / 86_400) + (seconds / 86_400 == 1 ? " day ago" : " days ago");
    }

    /** A dimension's id in words. */
    public static String worldWords(String dimension) {
        if (dimension == null) return "";
        return switch (dimension) {
            case VanillaIds.OVERWORLD -> "the Overworld";
            case VanillaIds.THE_NETHER -> "the Nether";
            case VanillaIds.THE_END -> "the End";
            default -> dimension;
        };
    }

    private static String clean(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("[\\p{Cntrl}§]", " ").replaceAll("\\s+", " ").strip();
        return flat.length() <= 120 ? flat : flat.substring(0, 120);
    }

    // ----- Kept across restarts -----

    /** What is kept of one peer: everything but a person's place and the claims, which hold a game day at most. */
    public record PeerRow(UUID id, String name, Kind kind, long firstMet, long lastSeen, long touched, String lastWorld, Place teamPlace,
                          List<Note> notes, List<Episode> episodes, Map<String, Integer> given, Map<String, Integer> received) { }

    /** The ledger as kept: claims and people's places are not (nothing said is kept, and a place only while in sight). */
    public record Snapshot(UUID self, List<PeerRow> peers) { }

    public Snapshot snapshot() {
        var rows = new ArrayList<PeerRow>();
        for (Peer p : peers.values()) {
            var notes = new ArrayList<Note>();
            for (var byTopic : p.notes.values()) notes.addAll(byTopic.values());
            rows.add(new PeerRow(p.id, p.name, p.kind, p.firstMet, p.lastSeen, p.touched, p.lastWorld, p.kind == Kind.AI_PLAYER ? p.place : null,
                    List.copyOf(notes), List.copyOf(p.episodes), Map.copyOf(p.given), Map.copyOf(p.received)));
        }
        return new Snapshot(self, List.copyOf(rows));
    }

    /** The ledger from what was kept, or an empty one; rows it cannot read are left out, and the bounds hold. */
    public static PeerLedger restore(UUID self, Snapshot snapshot) {
        var ledger = new PeerLedger(self);
        if (snapshot == null || snapshot.peers() == null) return ledger;
        for (PeerRow row : snapshot.peers()) {
            if (row == null || row.id() == null || row.id().equals(self) || row.kind() == null) continue;
            Peer peer = ledger.peer(row.id(), row.name(), row.kind(), row.touched());
            peer.firstMet = row.firstMet();
            peer.lastSeen = row.lastSeen();
            peer.lastWorld = row.lastWorld();
            peer.place = row.kind() == Kind.AI_PLAYER ? row.teamPlace() : null;
            if (row.notes() != null) for (Note note : row.notes()) if (note != null && note.topic() != null && note.source() != null
                    && note.source() != Source.SAID && note.text() != null) put(peer, note);
            if (row.episodes() != null) for (Episode e : row.episodes()) if (e != null && e.text() != null) peer.episodes.addLast(e);
            while (peer.episodes.size() > MAX_EPISODES) peer.episodes.removeFirst();
            if (row.given() != null) peer.given.putAll(row.given());
            if (row.received() != null) peer.received.putAll(row.received());
        }
        return ledger;
    }
}
