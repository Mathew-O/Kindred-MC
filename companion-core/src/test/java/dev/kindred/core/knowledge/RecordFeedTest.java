package dev.kindred.core.knowledge;

import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.core.wire.Listener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that the World Record's claims become what everyone knows of who owns which chunk, that a
 * capture or a release ends the old owner's claim, that "forget that" closes a fact and keeps it, and that replaying
 * the Record rebuilds the same web, which agrees with the territory ledger.
 */
class RecordFeedTest {
    private static WorldEvent e(long id, long tick, String kind, String faction, String chunk, Map<String, String> data) {
        return new WorldEvent(id, tick, 0, "w", kind, null, faction, chunk, data, WorldEvent.HowKnown.RECORDED);
    }

    @Test
    void claimsCapturesAndCorrectionsFromTheRecord() {
        String a = ClaimLedger.chunk("minecraft:overworld", 4, 4), b = ClaimLedger.chunk("minecraft:overworld", 5, 4);
        var events = new ArrayList<>(List.of(
                e(1, 10, ClaimLedger.CLAIM, "red", a, Map.of()),
                e(2, 20, ClaimLedger.CLAIM, "red", b, Map.of()),
                e(3, 30, ClaimLedger.CAPTURE, "blue", b, Map.of("from", "red")),
                e(4, 40, ClaimLedger.UNCLAIM, "red", a, Map.of()),
                e(5, 50, "death", "x", "", Map.of())));
        var world = RecordFeed.replay(events);
        var anyone = KnowledgeWeb.Viewer.of(new Listener(UUID.randomUUID(), "p", true, Set.of(), "", "", "", false, null, null));
        assertTrue(world.about(anyone, "faction:red", KnowledgeWeb.Verb.CLAIMED, 60).isEmpty(), "red holds nothing: captured, then released");
        var blue = world.about(anyone, "faction:blue", KnowledgeWeb.Verb.CLAIMED, 60);
        assertEquals(1, blue.size());
        assertEquals("chunk:" + b, blue.getFirst().object());
        assertEquals(List.of(3L), blue.getFirst().provenance().events());
        assertEquals(2, world.about(anyone, "faction:red", KnowledgeWeb.Verb.CLAIMED, 25).size(),
                "at tick 25, red held both");
        // The web agrees with the territory ledger, from the same events.
        var owners = new java.util.TreeMap<String, String>();
        for (var r : world.query(anyone, 60, r -> r.verb() == KnowledgeWeb.Verb.CLAIMED)) owners.put(r.object().substring("chunk:".length()), r.subject().substring("faction:".length()));
        var ledger = ClaimLedger.INSTANCE.empty();
        for (var ev : events) ledger = ClaimLedger.INSTANCE.apply(ledger, ev);
        assertEquals(ledger, owners);
        // "Forget that": the correction closes the relation, kept, with itself as the reason.
        long id = blue.getFirst().id();
        events.add(e(6, 70, RecordFeed.CORRECTION, "", "", Map.of("relation", String.valueOf(id))));
        var corrected = RecordFeed.replay(events);
        assertTrue(corrected.about(anyone, "faction:blue", KnowledgeWeb.Verb.CLAIMED, 80).isEmpty());
        assertEquals(6L, corrected.get(id).orElseThrow().closedBy());
        // Replaying twice gives the same web.
        var again = RecordFeed.replay(events);
        assertEquals(corrected.size(), again.size());
        assertEquals(corrected.get(id), again.get(id));
    }
}
