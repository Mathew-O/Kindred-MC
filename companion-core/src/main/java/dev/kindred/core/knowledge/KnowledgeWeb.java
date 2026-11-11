package dev.kindred.core.knowledge;

import dev.kindred.core.wire.HowKnown;
import dev.kindred.core.wire.Listener;
import dev.kindred.core.wire.Visibility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * In plain terms: what is known about the world, as things (players, people, factions, places, claims, matches,
 * promises) and how they stand to one another ("Wren lives at the oak hall", "Rook was seen at 120, 70, -40"), each
 * with where it came from, from when until when it holds, who may know it, and how sure it is. Something only said
 * stays a claim until the game confirms it; a "forget that" closes a fact with the correction, never deletes it; and
 * every question names who is asking, so the answer holds only what that one may know, by who they are, never by
 * how near they stand.
 *
 * Technical: F-32's pure core. Entities and relations in memory, owner-thread only; the adapter persists them through
 * the ordered writer and feeds them from F-31's events (staged as plain ids) and from the Wire's told messages.
 * Visibility is the relation's own and keyed by its group ({@link Relation#group}); {@link #query} filters by the
 * viewer's memberships alone. Lapses: a sighting after a game day, an unconfirmed claim after a game day.
 */
public final class KnowledgeWeb {
    /** A game day in ticks: how long a sighting or an unconfirmed claim holds. */
    public static final long DAY = 24_000;
    /** At most this many relations held: the oldest lapsed go first; open facts and corrections are never dropped. */
    public static final int MAX_RELATIONS = 20_000;

    /** What a thing is. */
    public enum Kind { PLAYER, PERSON, FACTION, SETTLEMENT, PLACE, ITEM, CLAIM, MATCH, PROMISE }

    /** How two things stand. */
    public enum Verb {
        OWNS, LIVES_AT, CLAIMED, SAW_AT, NEEDS, HAS_SPARE, PROMISED, OWES, ALLIED, ENEMY, FEARS, FRIENDS_WITH, MEMBER_OF,
        /** F-32b, from the peer ledger: what a player is doing, given and received items, a thing seen happen between them. */
        DOING, GAVE, RECEIVED, WITNESSED,
        /** F-32b, from the atlas: a resource found at a place, a place to shelter, a stretch of ground explored. */
        FOUND, SHELTERS_AT, EXPLORED,
        /** F-34c, team talk: what a teammate put into or took out of the team's store, and what it found gone from one. */
        STORED, TOOK, MISSING;

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** A thing, by a stable id ("player:<uuid>", "faction:oak"). */
    public record Entity(String id, Kind kind, String name) {
        public Entity { Objects.requireNonNull(id); Objects.requireNonNull(kind); name = name == null ? "" : name; }
    }

    /** Where a relation comes from: the events it rests on and how they are known. */
    public record Provenance(List<Long> events, HowKnown how) {
        public Provenance { events = List.copyOf(events); Objects.requireNonNull(how); }
    }

    /**
     * One fact: {@code subject verb object} with an optional value ("40 iron"), its provenance, from when until when
     * (exclusive; {@link Long#MAX_VALUE} for open), the event that closed it if corrected, who may know it ({@code
     * visibility} within {@code group}: the faction, team, side or settlement id; the subject's id for self), and how
     * sure it is (0 to 1).
     */
    public record Relation(long id, String subject, Verb verb, String object, String value, Provenance provenance, long from, long until,
                           Long closedBy, Visibility visibility, String group, double weight) {
        public Relation {
            Objects.requireNonNull(subject);
            Objects.requireNonNull(verb);
            object = object == null ? "" : object;
            value = value == null ? "" : value;
            Objects.requireNonNull(provenance);
            Objects.requireNonNull(visibility);
            group = group == null ? "" : group;
            weight = Math.max(0, Math.min(1, weight));
        }

        public boolean claimed() { return provenance.how() == HowKnown.CLAIMED; }

        /** Whether it holds at {@code tick}: begun, not lapsed, not closed. */
        public boolean validAt(long tick) { return closedBy == null && tick >= from && tick < until; }

        Relation closed(long at, long event) {
            return new Relation(id, subject, verb, object, value, provenance, from, Math.min(until, at), event, visibility, group, weight);
        }
    }

    /** Who is asking: their own id and memberships (the Wire's listener, its place ignored). */
    public record Viewer(Listener who) {
        public Viewer { Objects.requireNonNull(who); }

        public static Viewer of(Listener who) { return new Viewer(who); }

        /** Whether this viewer may know a relation, by relation alone. */
        public boolean mayKnow(Relation r) {
            return mayKnow(r, null);
        }

        /** The same, in a web kept by {@code owner}, who knows all of its own web. */
        boolean mayKnow(Relation r, java.util.UUID owner) {
            if (owner != null && owner.equals(who.id())) return true;
            return switch (r.visibility()) {
                case PUBLIC -> true;
                case OPERATOR -> who.operator();
                case SELF -> r.group().equals(who.id().toString()) || r.group().equals("player:" + who.id()) || r.group().equals("person:" + who.id());
                case FACTION -> !who.faction().isEmpty() && who.faction().equals(r.group());
                case TEAM -> who.teams().contains(r.group());
                case SIDE -> !who.side().isEmpty() && who.side().equals(r.group());
                case MATCH -> !who.match().isEmpty() && who.match().equals(r.group());
                case SETTLEMENT -> !who.settlement().isEmpty() && who.settlement().equals(r.group());
            };
        }
    }

    private final java.util.UUID owner;
    private final Map<String, Entity> entities = new LinkedHashMap<>();
    private final Map<Long, Relation> relations = new LinkedHashMap<>();
    private long nextId = 1;

    /** The world's web, kept by nobody: every viewer sees only what their relations allow. */
    public KnowledgeWeb() { this(null); }

    /**
     * One player's web: what it knows. It sees all of it; anyone it would tell sees only what their relation to each fact
     * allows (a runner's position told to a fellow runner is still the runners' side's, never a hunter's).
     */
    public KnowledgeWeb(java.util.UUID owner) { this.owner = owner; }

    public Optional<java.util.UUID> owner() { return Optional.ofNullable(owner); }

    public Entity entity(String id, Kind kind, String name) {
        var e = new Entity(id, kind, name);
        entities.merge(id, e, (old, neu) -> neu.name().isEmpty() ? old : neu);
        return entities.get(id);
    }

    public Optional<Entity> entity(String id) { return Optional.ofNullable(entities.get(id)); }

    /**
     * Adds a fact from {@code how} at {@code tick}, resting on {@code events}. A sighting, and anything only said,
     * lapses in a game day; a told or recorded fact holds until replaced or closed. The same subject, verb and object
     * already open and visible to the same group is replaced: it ends at {@code tick}, so its history stays.
     */
    public Relation add(String subject, Verb verb, String object, String value, HowKnown how, List<Long> events, long tick,
                        Visibility visibility, String group) {
        long until = verb == Verb.SAW_AT || how == HowKnown.CLAIMED ? tick + DAY : Long.MAX_VALUE;
        double weight = switch (how) { case RECORDED -> 1.0; case TOLD -> 0.8; case CLAIMED -> 0.3; };
        for (var r : List.copyOf(relations.values())) {
            if (r.closedBy() == null && r.subject().equals(subject) && r.verb() == verb && r.object().equals(object)
                    && r.visibility() == visibility && r.group().equals(group == null ? "" : group) && r.until() > tick) {
                relations.put(r.id(), new Relation(r.id(), r.subject(), r.verb(), r.object(), r.value(), r.provenance(), r.from(),
                        Math.min(r.until(), tick), null, r.visibility(), r.group(), r.weight()));
            }
        }
        var rel = new Relation(nextId++, subject, verb, object, value, new Provenance(events, how), tick, until, null, visibility, group, weight);
        relations.put(rel.id(), rel);
        bound(tick);
        return rel;
    }

    /**
     * An event confirms a claimed or told fact: it becomes recorded, sure, and no longer lapses (a sighting still does).
     */
    public Optional<Relation> confirm(long relationId, long event) {
        var r = relations.get(relationId);
        if (r == null || r.closedBy() != null) return Optional.empty();
        var events = new ArrayList<>(r.provenance().events());
        events.add(event);
        long until = r.verb() == Verb.SAW_AT ? r.until() : Long.MAX_VALUE;
        var c = new Relation(r.id(), r.subject(), r.verb(), r.object(), r.value(), new Provenance(events, HowKnown.RECORDED), r.from(), until,
                null, r.visibility(), r.group(), 1.0);
        relations.put(r.id(), c);
        return Optional.of(c);
    }

    /** Every open fact matching {@code which} ended at {@code tick} (it no longer holds; its history stays); how many. */
    public int end(Predicate<Relation> which, long tick) {
        int n = 0;
        for (var r : List.copyOf(relations.values())) {
            if (r.closedBy() == null && r.until() > tick && which.test(r)) {
                relations.put(r.id(), new Relation(r.id(), r.subject(), r.verb(), r.object(), r.value(), r.provenance(), r.from(),
                        Math.max(r.from(), tick), null, r.visibility(), r.group(), r.weight()));
                n++;
            }
        }
        return n;
    }

    /** "Forget that": the fact closed at {@code tick} with the correction event as its end, never deleted. */
    public Optional<Relation> correct(long relationId, long correctionEvent, long tick) {
        var r = relations.get(relationId);
        if (r == null || r.closedBy() != null) return Optional.empty();
        var c = r.closed(tick, correctionEvent);
        relations.put(r.id(), c);
        return Optional.of(c);
    }

    /** Every relation this viewer may know that holds at {@code tick} and matches {@code filter}, surest and newest first. */
    public List<Relation> query(Viewer viewer, long tick, Predicate<Relation> filter) {
        Objects.requireNonNull(viewer, "every query names its viewer");
        var out = new ArrayList<Relation>();
        for (var r : relations.values()) if (r.validAt(tick) && viewer.mayKnow(r, owner) && filter.test(r)) out.add(r);
        out.sort(Comparator.comparingDouble(Relation::weight).reversed().thenComparing(Comparator.comparingLong(Relation::from).reversed()));
        return out;
    }

    /** What this viewer may know of {@code subject} by {@code verb} now. */
    public List<Relation> about(Viewer viewer, String subject, Verb verb, long tick) {
        return query(viewer, tick, r -> r.subject().equals(subject) && (verb == null || r.verb() == verb));
    }

    /** A relation's whole history, closed ones included, for operators' audits only. */
    public List<Relation> history(Viewer viewer, String subject) {
        if (!viewer.who().operator()) return List.of();
        return relations.values().stream().filter(r -> r.subject().equals(subject)).toList();
    }

    public int size() { return relations.size(); }

    /** Every relation held, open or closed, in the order added: for readers that need the whole web (F-32b's legacy reading). */
    public java.util.Collection<Relation> all() { return java.util.Collections.unmodifiableCollection(relations.values()); }

    public Optional<Relation> get(long id) { return Optional.ofNullable(relations.get(id)); }

    /** Keeps at most {@link #MAX_RELATIONS}: lapsed facts go, oldest first; open facts and corrections are never dropped. */
    private void bound(long tick) {
        if (relations.size() <= MAX_RELATIONS) return;
        var it = relations.values().iterator();
        while (relations.size() > MAX_RELATIONS && it.hasNext()) {
            var r = it.next();
            if (r.closedBy() == null && r.until() <= tick) it.remove();
        }
    }
}
