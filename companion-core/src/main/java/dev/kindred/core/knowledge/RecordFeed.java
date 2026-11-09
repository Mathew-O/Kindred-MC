package dev.kindred.core.knowledge;

import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.core.wire.HowKnown;
import dev.kindred.core.wire.Visibility;

import java.util.List;
import java.util.Optional;

/**
 * In plain terms: what the World Record tells the web that everyone may know: which faction claimed which chunk, and
 * when a chunk is released or captured, that the old owner's claim has ended; and a "forget that", which closes the fact
 * it names with itself as the reason. Built from the Record alone, so a restart rebuilds it the same.
 *
 * Technical: F-32b's feed from F-31. Claims are public (the spec's notifications), kept as {@code faction:<id> claimed
 * chunk:<place>}, recorded, resting on the event; an unclaim or a capture ends the chunk's other open claims at its
 * tick. A {@code correction} event (data {@code relation}, the web's relation id) closes that relation. Other kinds are
 * not the web's.
 */
public final class RecordFeed {
    private RecordFeed() { }

    public static final String CORRECTION = "correction";

    /** The kinds the web reads from the Record. */
    public static final java.util.Set<String> KINDS = java.util.Set.of(ClaimLedger.CLAIM, ClaimLedger.UNCLAIM, ClaimLedger.CAPTURE, CORRECTION);

    /** Applies one recorded event to the world's web; the relation it added, if any. */
    public static Optional<KnowledgeWeb.Relation> apply(KnowledgeWeb world, WorldEvent e) {
        long tick = Math.max(0, e.tick());
        switch (e.kind()) {
            case ClaimLedger.CLAIM, ClaimLedger.CAPTURE, ClaimLedger.UNCLAIM -> {
                if (e.place().isEmpty() || e.subject().isEmpty()) return Optional.empty();
                String chunk = "chunk:" + e.place(), owner = "faction:" + e.subject();
                if (e.kind().equals(ClaimLedger.UNCLAIM)) {
                    world.end(r -> r.verb() == KnowledgeWeb.Verb.CLAIMED && r.object().equals(chunk) && r.subject().equals(owner), tick);
                    return Optional.empty();
                }
                world.end(r -> r.verb() == KnowledgeWeb.Verb.CLAIMED && r.object().equals(chunk) && !r.subject().equals(owner), tick);
                world.entity(owner, KnowledgeWeb.Kind.FACTION, e.subject());
                world.entity(chunk, KnowledgeWeb.Kind.CLAIM, e.place());
                return Optional.of(world.add(owner, KnowledgeWeb.Verb.CLAIMED, chunk, "", HowKnown.RECORDED, List.of(e.id()), tick, Visibility.PUBLIC, ""));
            }
            case CORRECTION -> {
                try {
                    world.correct(Long.parseLong(e.data().getOrDefault("relation", "-1")), e.id(), tick);
                } catch (NumberFormatException ignored) { }
                return Optional.empty();
            }
            default -> { return Optional.empty(); }
        }
    }

    /** The world's web rebuilt from the Record's events, in its order. */
    public static KnowledgeWeb replay(List<WorldEvent> events) {
        var world = new KnowledgeWeb();
        for (var e : events) apply(world, e);
        return world;
    }
}
