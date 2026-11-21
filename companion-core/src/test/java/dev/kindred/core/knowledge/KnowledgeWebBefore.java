package dev.kindred.core.knowledge;

import dev.kindred.core.knowledge.KnowledgeWeb.Provenance;
import dev.kindred.core.knowledge.KnowledgeWeb.Relation;
import dev.kindred.core.knowledge.KnowledgeWeb.Verb;
import dev.kindred.core.wire.HowKnown;
import dev.kindred.core.wire.Visibility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * In plain terms: the knowledge web's changes exactly as they were before F-32d's index, kept only so a test can make
 * the same changes to both and compare what each holds.
 *
 * Technical: the bodies of {@code add}, {@code confirm}, {@code end}, {@code correct} and {@code bound} copied from
 * main at 039c8b9, with the bound a parameter as the indexed web's test constructor takes it. Test code only.
 */
final class KnowledgeWebBefore {
    private final Map<Long, Relation> relations = new LinkedHashMap<>();
    private final int max;
    private long nextId = 1;

    KnowledgeWebBefore(int max) { this.max = max; }

    List<Relation> all() { return List.copyOf(relations.values()); }

    Relation add(String subject, Verb verb, String object, String value, HowKnown how, List<Long> events, long tick,
                 Visibility visibility, String group) {
        long until = verb == Verb.SAW_AT || how == HowKnown.CLAIMED ? tick + KnowledgeWeb.DAY : Long.MAX_VALUE;
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

    Optional<Relation> confirm(long relationId, long event) {
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

    int end(Predicate<Relation> which, long tick) {
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

    Optional<Relation> correct(long relationId, long correctionEvent, long tick) {
        var r = relations.get(relationId);
        if (r == null || r.closedBy() != null) return Optional.empty();
        var c = r.closed(tick, correctionEvent);
        relations.put(r.id(), c);
        return Optional.of(c);
    }

    private void bound(long tick) {
        if (relations.size() <= max) return;
        var it = relations.values().iterator();
        while (relations.size() > max && it.hasNext()) {
            var r = it.next();
            if (r.closedBy() == null && r.until() <= tick) it.remove();
        }
    }
}
