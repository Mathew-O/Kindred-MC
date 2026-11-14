package dev.kindred.core.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the list of kinds the World Record keeps (F-31): Kindred's own kinds are on it with the very
 * strings already saved, an integration adds a kind of its own without touching core, and no two owners share a kind.
 */
class EventKindsTest {
    /** The saved values, spelled out: a registry must never change what is already on disk. */
    private static final Set<String> SAVED = Set.of("action", "action.folded", "action.pruned", "journal", "claim", "unclaim", "capture", "death", "match",
            "ledger_check", "faction.create", "faction.invite", "faction.join", "faction.leave", "faction.rank", "faction.wish",
            "faction.home", "faction.colour", "faction.disband", "power.online", "power.loss", "power.set", "account.seen",
            "faction.vulnerable", "faction.secure", "coins", "safe.zone", "market.sale", "market.buy", "market.baseline",
            "market.signal", "auction.list", "auction.bid", "auction.close", "auction.cancel", "auction.order",
            "auction.order.fill", "auction.order.cancel", "auction.delivering", "auction.delivered", "message", "correction",
            "team.state", "chronicle.page", "policy.set");

    @Test
    void kindredsOwnKindsAreRegisteredWithTheirSavedValues() {
        var core = new TreeSet<String>();
        for (var kind : EventKinds.all()) if (kind.owner().equals(EventKinds.CORE)) core.add(kind.id());
        assertEquals(new TreeSet<>(SAVED), core);
    }

    @Test
    void anIntegrationRegistersItsOwnKindAndNoOneElseMayTakeIt() {
        String kind = "test.battle." + Long.toHexString(System.nanoTime());
        assertFalse(EventKinds.known(kind));
        assertEquals(kind, EventKinds.register(kind, "test-integration"));
        assertTrue(EventKinds.known(kind));
        assertEquals("test-integration", EventKinds.of(kind).orElseThrow().owner());
        EventKinds.register(kind, "test-integration"); // Again by its owner: a no-op.
        assertThrows(IllegalStateException.class, () -> EventKinds.register(kind, "another"));
        assertThrows(IllegalStateException.class, () -> EventKinds.register(DeathLedger.DEATH, "test-integration"), "core's kinds are core's");
    }

    @Test
    void aKindIsSpelledAsTheRecordAllows() {
        assertThrows(IllegalArgumentException.class, () -> EventKinds.register("Battle", "x"));
        assertThrows(IllegalArgumentException.class, () -> EventKinds.register("", "x"));
        assertThrows(IllegalArgumentException.class, () -> EventKinds.register("test.ok", " "));
        assertFalse(EventKinds.known(null));
    }
}
