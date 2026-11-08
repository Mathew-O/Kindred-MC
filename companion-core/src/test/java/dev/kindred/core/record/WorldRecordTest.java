package dev.kindred.core.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.match.MatchRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the World Record's events and ledgers (F-31): an event's details survive any text, each
 * ledger is rebuilt from a recorded stream of events, the agreement check catches a ledger that drifted, and the
 * matches come back after a restart with no running match left able to strike.
 */
class WorldRecordTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    private long id = 1;

    @Test
    void eventDataRoundTripsAnyText() {
        var data = new LinkedHashMap<String, String>();
        data.put("plain", "oak logs");
        data.put("odd & = %", "50% & more =\r\nlines");
        data.put("empty", "");
        assertEquals(data, EventData.decode(EventData.encode(data)));
        assertEquals(List.copyOf(data.keySet()), List.copyOf(EventData.decode(EventData.encode(data)).keySet()), "order kept");
        assertEquals(Map.of(), EventData.decode(""));
        assertThrows(IllegalArgumentException.class, () -> EventData.decode("novalue"));
        assertThrows(IllegalArgumentException.class, () -> EventData.decode("k=%2"));
    }

    @Test
    void anEventIsValidatedAndImmutable() {
        assertThrows(IllegalArgumentException.class, () -> event("Bad Kind", null, "", ""));
        assertThrows(IllegalArgumentException.class, () -> new WorldEvent(0, -2, 0, "w", "death", null, "", "", Map.of(), WorldEvent.HowKnown.RECORDED));
        assertThrows(IllegalArgumentException.class, () -> WorldEvent.recorded(0, 0, "w", "death", null, "x".repeat(257), "", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> WorldEvent.recorded(0, 0, "w", "death", null, "", "", Map.of("k", "v".repeat(20_000))));
        var mutable = new LinkedHashMap<String, String>(Map.of("k", "v"));
        var event = WorldEvent.recorded(0, 0, "w", "death", null, "", "", mutable);
        mutable.put("k", "changed");
        assertEquals("v", event.get("k", ""));
        assertThrows(UnsupportedOperationException.class, () -> event.data().put("x", "y"));
        assertEquals(WorldEvent.HowKnown.CLAIMED, WorldEvent.HowKnown.parse("claimed"));
    }

    @Test
    void claimsAreRebuiltFromClaimsReleasesAndCaptures() {
        String one = ClaimLedger.chunk("minecraft:overworld", 0, 0), two = ClaimLedger.chunk("minecraft:overworld", 1, 0);
        var events = List.of(
                event(ClaimLedger.CLAIM, a, "red", one),
                event(ClaimLedger.CLAIM, b, "blue", two),
                event(ClaimLedger.UNCLAIM, a, "blue", one), // Not blue's: nothing changes.
                event(ClaimLedger.CAPTURE, a, "red", two, Map.of("from", "blue")),
                event(ClaimLedger.UNCLAIM, a, "red", one),
                event("death", a, "", "")); // Not a claim kind: ignored.
        assertEquals(Map.of(two, "red"), ClaimLedger.INSTANCE.replay(events));
        assertEquals(Map.of(), ClaimLedger.INSTANCE.replay(List.of()));
    }

    @Test
    void deathsAreCountedPerPlayer() {
        var events = List.of(event(DeathLedger.DEATH, a, "", ""), event(DeathLedger.DEATH, b, "", ""), event(DeathLedger.DEATH, a, "", ""),
                event(DeathLedger.DEATH, null, "", ""));
        assertEquals(Map.of(a, 2, b, 1), DeathLedger.INSTANCE.replay(events));
    }

    @Test
    void theActionLedgerIsItsLastEventPerAction() {
        UUID action = UUID.randomUUID();
        var events = List.of(
                event(ActionLedgerView.ACTION, a, action.toString(), "", Map.of("state", "PREPARED", "revision", "0")),
                event(ActionLedgerView.ACTION, a, action.toString(), "", Map.of("state", "DISPATCHED", "revision", "1")),
                event(ActionLedgerView.ACTION, a, "not an id", "", Map.of("state", "PREPARED", "revision", "0")));
        assertEquals(Map.of(action, new ActionLedgerView.Row("DISPATCHED", 1)), ActionLedgerView.INSTANCE.replay(events));
    }

    @Test
    void theAgreementCheckCatchesALedgerThatDrifted() {
        var events = List.of(event(ClaimLedger.CLAIM, a, "red", "w:0:0"));
        assertTrue(ClaimLedger.INSTANCE.check(events, Map.of("w:0:0", "red")).agrees());
        var drifted = ClaimLedger.INSTANCE.check(events, Map.of("w:0:0", "blue"));
        assertFalse(drifted.agrees());
        assertEquals("claims", drifted.ledger());
        assertEquals(Map.of("w:0:0", "red"), drifted.fromRecord());
        Ledgers kept = Ledgers.replay(events);
        assertTrue(kept.agrees(events));
        assertFalse(new Ledgers(kept.matches(), Map.of(), kept.deaths(), kept.factions(), kept.power(), kept.coins()).agrees(events));
    }

    @Test
    void matchesAreRebuiltAndRestoredWithNoLeaveToStrikeSurvivingARestart() {
        var registry = new MatchRegistry();
        var duel = registry.proposeDuel(List.of(a, b), 20 * 60).match().orElseThrow();
        registry.join(duel.id(), a);
        registry.join(duel.id(), b);
        registry.start(duel.id(), 0);
        registry.tick(MatchRegistry.COUNTDOWN_TICKS);
        assertTrue(registry.mayAttack(a, b));
        var hunt = registry.proposeManhunt(List.of(c), List.of(UUID.randomUUID()), 200).match().orElseThrow();
        var events = new ArrayList<WorldEvent>();
        for (var stored : registry.drainChanges()) events.add(withId(MatchLedger.event(70, 1, "w", stored)));
        assertTrue(registry.drainChanges().isEmpty(), "drained once");
        var ledger = MatchLedger.INSTANCE.replay(events);
        assertEquals(Set.of(duel.id(), hunt.id()), ledger.keySet());
        assertEquals(1200, ledger.get(duel.id()).limit());
        assertEquals(200, ledger.get(hunt.id()).headStart());
        assertEquals(registry.stored(duel.id()).orElseThrow(), ledger.get(duel.id()));

        var restarted = new MatchRegistry();
        assertEquals(2, restarted.restore(ledger.values()));
        assertFalse(restarted.mayAttack(a, b), "a duel the restart cut short is stopped");
        assertEquals(MatchRegistry.State.ENDED, restarted.get(duel.id()).orElseThrow().state());
        assertEquals("Stopped: the server restarted", restarted.get(duel.id()).orElseThrow().result());
        assertEquals(1200, restarted.limit(duel.id()));
        assertEquals(200, restarted.headStart(hunt.id()));
        assertEquals(2, restarted.drainChanges().size(), "the stops are themselves changes for the Record");
        assertThrows(IllegalStateException.class, () -> restarted.restore(ledger.values()));
        var ended = MatchLedger.decode(MatchLedger.encode(restarted.stored(duel.id()).orElseThrow()));
        assertEquals(restarted.stored(duel.id()).orElseThrow(), ended);
    }

    @Test
    void anEndedMatchKeepsItsWinnerThroughTheRecord() {
        var registry = new MatchRegistry();
        var duel = registry.propose(MatchRegistry.Mode.DUEL, List.of(a, b)).match().orElseThrow();
        registry.join(duel.id(), a);
        registry.join(duel.id(), b);
        registry.start(duel.id(), 0);
        registry.tick(MatchRegistry.COUNTDOWN_TICKS);
        registry.fallen(b);
        var events = registry.drainChanges().stream().map(s -> withId(MatchLedger.event(80, 1, "w", s))).toList();
        var restarted = new MatchRegistry();
        restarted.restore(MatchLedger.INSTANCE.replay(events).values());
        var kept = restarted.get(duel.id()).orElseThrow();
        assertEquals(a, kept.winner());
        assertEquals("fell", kept.result());
        assertTrue(restarted.drainChanges().isEmpty(), "an ended match is put back as it was, with nothing new to record");
    }

    @Test
    void theLedgerSetIgnoresKindsItDoesNotRead() {
        Ledgers start = Ledgers.EMPTY;
        assertSame(start, start.apply(event("sale", a, "", "")));
        assertTrue(Ledgers.kinds().containsAll(Set.of("match", "claim", "unclaim", "capture", "death")));
    }

    private WorldEvent event(String kind, UUID actor, String subject, String place) { return event(kind, actor, subject, place, Map.of()); }

    private WorldEvent event(String kind, UUID actor, String subject, String place, Map<String, String> data) {
        return withId(WorldEvent.recorded(id, 1_000 + id, "w", kind, actor, subject, place, data));
    }

    private WorldEvent withId(WorldEvent draft) { return draft.withId(id++); }
}
