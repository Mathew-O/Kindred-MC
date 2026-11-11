package dev.kindred.core.knowledge;

import dev.kindred.core.atlas.Atlas;
import dev.kindred.core.wire.HowKnown;
import dev.kindred.core.social.PeerLedger;
import dev.kindred.core.wire.Visibility;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * In plain terms: what an AI player already knew before the knowledge web, read once from its old files into its own
 * web: of each other player, what it was doing, needs, could spare, where its team last had it, what passed between
 * them and what was seen to happen; of the world, where it found each resource and how much, the places it sheltered,
 * the ground it explored and the spots it was hurt. Nothing is made up: each fact keeps the tick it was learnt at and
 * how it was known (told by the team, or seen by the game), and nothing said by a person was kept in those files.
 *
 * Technical: F-32b's one-time reading of {@code <uuid>.peers.json} (E-50's {@link PeerLedger.Snapshot}) and {@code
 * <uuid>.atlas.json} (F-30's {@link Atlas.Snapshot}) into a player's {@link KnowledgeWeb}. Team notes are
 * {@link HowKnown#TOLD} and visible to the team ({@code team}); the game's record ({@code GAME} notes, gifts, episodes,
 * the atlas) is {@link HowKnown#RECORDED} and the player's own ({@link Visibility#SELF}). Reading the same snapshot again
 * adds nothing: a relation already open with the same subject, verb, object and value is left as it is.
 */
public final class LegacyMemory {
    private LegacyMemory() { }

    /** What was read: relations added, and those already there. */
    public record Read(int added, int kept) {
        Read plus(boolean add) { return add ? new Read(added + 1, kept) : new Read(added, kept + 1); }
    }

    /** The peer ledger as kept, into {@code web} (the snapshot's own player's), team facts for {@code team}. */
    public static Read peers(KnowledgeWeb web, PeerLedger.Snapshot snapshot, String team) {
        Objects.requireNonNull(web);
        var read = new Read(0, 0);
        if (snapshot == null || snapshot.peers() == null) return read;
        String self = WebFeed.entityOf(snapshot.self(), false);
        for (var p : snapshot.peers()) {
            if (p == null || p.id() == null) continue;
            boolean person = p.kind() == PeerLedger.Kind.PERSON;
            String who = WebFeed.entityOf(p.id(), person);
            web.entity(who, person ? KnowledgeWeb.Kind.PERSON : KnowledgeWeb.Kind.PLAYER, p.name());
            if (p.notes() != null) for (var n : p.notes()) {
                if (n == null || n.topic() == null || n.text() == null || n.text().isBlank() || n.source() == PeerLedger.Source.SAID) continue;
                if (n.correction() != null) continue; // Taken back: never told again.
                boolean team1 = n.source() == PeerLedger.Source.TEAM;
                HowKnown how = team1 ? HowKnown.TOLD : HowKnown.RECORDED;
                Visibility v = team1 ? Visibility.TEAM : Visibility.SELF;
                String group = team1 ? team : self;
                switch (n.topic()) {
                    case DOING -> read = read.plus(put(web, who, KnowledgeWeb.Verb.DOING, "", n.text(), how, n.at(), v, group));
                    case NEEDS -> { for (String item : items(n.text())) read = read.plus(put(web, who, KnowledgeWeb.Verb.NEEDS, item(item), item, how, n.at(), v, group)); }
                    case SPARE -> { for (String item : items(n.text())) read = read.plus(put(web, who, KnowledgeWeb.Verb.HAS_SPARE, item(item), item, how, n.at(), v, group)); }
                    default -> read = read.plus(put(web, who, KnowledgeWeb.Verb.WITNESSED, n.topic().name().toLowerCase(Locale.ROOT), n.text(), how, n.at(), v, group));
                }
            }
            if (p.teamPlace() != null) {
                var at = p.teamPlace();
                read = read.plus(put(web, who, KnowledgeWeb.Verb.SAW_AT, place(at.dimension(), at.x(), at.y(), at.z()), "", HowKnown.TOLD, p.touched(), Visibility.TEAM, team));
            }
            if (p.episodes() != null) for (var e : p.episodes()) {
                if (e != null && e.text() != null && !e.text().isBlank()) read = read.plus(put(web, who, KnowledgeWeb.Verb.WITNESSED, "", e.text(), HowKnown.RECORDED, e.at(), Visibility.SELF, self));
            }
            if (p.given() != null) for (var g : p.given().entrySet()) {
                if (g.getValue() != null && g.getValue() > 0) read = read.plus(put(web, self, KnowledgeWeb.Verb.GAVE, who, g.getValue() + " " + g.getKey(), HowKnown.RECORDED, p.touched(), Visibility.SELF, self));
            }
            if (p.received() != null) for (var r : p.received().entrySet()) {
                if (r.getValue() != null && r.getValue() > 0) read = read.plus(put(web, self, KnowledgeWeb.Verb.RECEIVED, who, r.getValue() + " " + r.getKey(), HowKnown.RECORDED, p.touched(), Visibility.SELF, self));
            }
        }
        return read;
    }

    /** The atlas as kept, into {@code owner}'s web: every fact the player's own, as the game recorded it. */
    public static Read atlas(KnowledgeWeb web, java.util.UUID owner, Atlas.Snapshot snapshot) {
        Objects.requireNonNull(web);
        var read = new Read(0, 0);
        if (snapshot == null) return read;
        String self = WebFeed.entityOf(owner, false);
        if (snapshot.chunks() != null) for (var c : snapshot.chunks()) {
            if (c == null) continue;
            if (c.visited()) read = read.plus(put(web, self, KnowledgeWeb.Verb.EXPLORED, "chunk:" + c.dimension() + " " + c.x() + " " + c.z(),
                    c.biome() == null ? "" : c.biome(), HowKnown.RECORDED, c.last(), Visibility.SELF, self));
            if (c.sightings() != null) for (var s : c.sightings()) {
                if (s == null || s.resource() == null || s.at() == null) continue;
                String what = "resource:" + s.resource().name().toLowerCase(Locale.ROOT);
                web.entity(what, KnowledgeWeb.Kind.ITEM, s.resource().name().toLowerCase(Locale.ROOT));
                read = read.plus(put(web, what, KnowledgeWeb.Verb.FOUND, place(s.at().dimension(), s.at().x(), s.at().y(), s.at().z()),
                        String.valueOf(s.count()), HowKnown.RECORDED, s.last(), Visibility.SELF, self));
            }
        }
        if (snapshot.danger() != null) for (var d : snapshot.danger()) {
            if (d == null || d.harms() == null || d.harms().isEmpty()) continue;
            long last = 0;
            int weight = 0, night = 0;
            for (var h : d.harms()) { last = Math.max(last, h.time()); weight += h.weight(); if (h.night()) night++; }
            read = read.plus(put(web, self, KnowledgeWeb.Verb.FEARS, place(d.dimension(), d.x(), d.y(), d.z()),
                    d.harms().size() + " harms, weight " + weight + ", " + night + " at night", HowKnown.RECORDED, last, Visibility.SELF, self));
        }
        if (snapshot.shelters() != null) for (var s : snapshot.shelters()) {
            if (s == null || s.at() == null) continue;
            read = read.plus(put(web, self, KnowledgeWeb.Verb.SHELTERS_AT, place(s.at().dimension(), s.at().x(), s.at().y(), s.at().z()),
                    s.kind() == null ? "" : s.kind(), HowKnown.RECORDED, 0, Visibility.SELF, self));
        }
        return read;
    }

    /** A place's entity id. */
    public static String place(String dimension, int x, int y, int z) {
        String d = dimension == null || dimension.isBlank() ? "overworld" : dimension;
        if (d.startsWith("minecraft:")) d = d.substring("minecraft:".length());
        return "place:" + d + " " + x + " " + y + " " + z;
    }

    private static boolean put(KnowledgeWeb web, String subject, KnowledgeWeb.Verb verb, String object, String value, HowKnown how, long tick,
                               Visibility v, String group) {
        String g = group == null ? "" : group;
        for (var r : web.all()) {
            if (r.closedBy() == null && r.until() == Long.MAX_VALUE && r.subject().equals(subject) && r.verb() == verb && r.object().equals(object)
                    && r.value().equals(value) && r.visibility() == v && r.group().equals(g)) return false;
            if (verb == KnowledgeWeb.Verb.SAW_AT && r.subject().equals(subject) && r.verb() == verb && r.object().equals(object) && r.from() == tick
                    && r.group().equals(g)) return false; // A lapsed sighting read before.
        }
        web.add(subject, verb, object, value, how, List.of(), Math.max(0, tick), v, g);
        return true;
    }

    private static List<String> items(String text) {
        return java.util.Arrays.stream(text.split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList();
    }

    /** "3 iron ingots" to "item:iron ingots". */
    private static String item(String words) {
        return "item:" + words.replaceFirst("^\\d+\\s+", "").toLowerCase(Locale.ROOT);
    }
}
