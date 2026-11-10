package dev.kindred.persistence;

import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.record.ActionLedgerView;
import dev.kindred.core.record.Ledgers;
import dev.kindred.core.record.WorldEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the action fold (F-31, from F-33's month-long cost check): a settled action's history becomes
 * one summary holding its final state, so the action ledger still agrees with the World Record; an action not yet
 * settled, or ever uncertain, is left whole; every other kind of event is kept exactly as written and still cannot be
 * deleted; ids only grow; a second fold finds nothing; and the summaries are there after a reopen.
 */
class ActionFoldStoreTest {
    @TempDir Path directory;
    private final List<SqliteCompanionStore> opened = new ArrayList<>();

    @AfterEach
    void closeStores() {
        for (SqliteCompanionStore store : opened) store.closeAsync().join();
    }

    private SqliteCompanionStore open() {
        SqliteCompanionStore store = SqliteCompanionStore.open(directory.resolve("companions.sqlite"), 10).join();
        opened.add(store);
        return store;
    }

    @Test
    void settledActionsFoldIntoTheirFinalRowAndTheLedgerStillAgrees() throws Exception {
        SqliteCompanionStore store = open();
        UUID id = store.create(new CreationRequest(UUID.randomUUID(), UUID.randomUUID(), "Juniper", UUID.randomUUID(), "minecraft:overworld"))
                .join().companion().id();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        // Settled: verified (3 events), failed (3), cancelled (2).
        var verified = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "deposit", "chest")).join();
        verified = store.transitionAction(store.transitionAction(verified.dispatched("8 logs"), 0).join().verified("chest 8", "moved"), 1).join();
        var failed = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "goto", "x")).join();
        failed = store.transitionAction(store.transitionAction(failed.dispatched("here"), 0).join().failed("there", "no route"), 1).join();
        var cancelled = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "toss", "y")).join();
        cancelled = store.transitionAction(cancelled.cancelled("stopped"), 0).join();
        // Not settled: still dispatched; uncertain.
        var live = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "mine", "z")).join();
        live = store.transitionAction(live.dispatched("ore"), 0).join();
        var uncertain = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "craft", "w")).join();
        uncertain = store.transitionAction(uncertain.uncertain("ended mid-way"), 0).join();
        var claim = store.record(WorldEvent.recorded(5, 9, "w", "claim", null, "red", "w:0:0", Map.of("by", "red"))).join();

        var ledgerBefore = store.allWorldEvents(Ledgers.kinds()).join();
        long highest = store.allWorldEvents(Set.of()).join().stream().mapToLong(WorldEvent::id).max().orElseThrow();
        assertTrue(store.checkActionLedger().join().agrees());

        var fold = store.foldSettledActions(Long.MAX_VALUE, 5_000).join();
        assertEquals(3, fold.actions(), "the verified, failed and cancelled actions");
        assertEquals(8, fold.eventsRemoved());
        assertTrue(fold.millis() >= 0);

        var agreement = store.checkActionLedger().join();
        assertTrue(agreement.agrees(), "the ledger rebuilt from summaries and live events is the table: " + agreement);
        var summaries = store.worldEvents(Set.of(ActionLedgerView.FOLDED), 0, 100).join();
        assertEquals(3, summaries.size());
        assertTrue(summaries.stream().allMatch(e -> e.id() > highest), "each summary a fresh, higher id");
        String verifiedId = verified.actionId().toString();
        var v = summaries.stream().filter(e -> e.subject().equals(verifiedId)).findFirst().orElseThrow();
        assertEquals("VERIFIED", v.get("state", ""));
        assertEquals("2", v.get("revision", ""));
        assertEquals("3", v.get("folded", ""));
        assertEquals(id, v.actor());
        // Untouched: the live and the uncertain action's events, and every ledger kind.
        var left = store.worldEvents(Set.of(ActionLedgerView.ACTION), 0, 100).join();
        assertEquals(Set.of(live.actionId().toString(), uncertain.actionId().toString()),
                Set.copyOf(left.stream().map(WorldEvent::subject).toList()));
        assertEquals(4, left.size(), "two events each, kept whole");
        assertEquals(ledgerBefore, store.allWorldEvents(Ledgers.kinds()).join(), "every ledger kind exactly as written");
        assertEquals(List.of(claim), store.worldEvents(Set.of("claim"), 0, 10).join());

        // A second fold finds nothing more; settled later, the dispatched action folds then.
        assertEquals(0, store.foldSettledActions(Long.MAX_VALUE, 5_000).join().actions());
        store.transitionAction(live.verified("ore 3", "mined"), 1).join();
        assertEquals(1, store.foldSettledActions(Long.MAX_VALUE, 5_000).join().actions());
        // Nothing newer than the window is folded.
        var fresh = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "eat", "bread")).join();
        store.transitionAction(fresh.cancelled("full"), 0).join();
        assertEquals(0, store.foldSettledActions(1, 5_000).join().actions(), "an action settled after the window's edge is left");
        assertTrue(store.checkActionLedger().join().agrees());
        store.closeAsync().join();

        // After a reopen: the summaries read back, the ledger agrees, and only action events can be deleted.
        var reopened = open();
        assertEquals(4, reopened.worldEvents(Set.of(ActionLedgerView.FOLDED), 0, 100).join().size());
        assertTrue(reopened.checkActionLedger().join().agrees());
        reopened.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("companions.sqlite")); var statement = connection.createStatement()) {
            for (String sql : List.of("DELETE FROM world_events WHERE kind = 'claim'",
                    "DELETE FROM world_events WHERE kind = '" + ActionLedgerView.FOLDED + "'",
                    "UPDATE world_events SET subject = 'blue' WHERE kind = '" + ActionLedgerView.ACTION + "'")) {
                var refused = assertThrows(SQLException.class, () -> statement.execute(sql), sql);
                assertTrue(refused.getMessage().contains("The World Record is append-only"), sql + ": " + refused.getMessage());
            }
            try (var row = statement.executeQuery("SELECT COUNT(*) FROM world_events WHERE kind = 'claim'")) { assertEquals(1, row.getInt(1)); }
        }
    }

    @Test
    void aFoldTakesAtMostItsShareAndTheRestNext() {
        SqliteCompanionStore store = open();
        UUID id = store.create(new CreationRequest(UUID.randomUUID(), UUID.randomUUID(), "Juniper", UUID.randomUUID(), "minecraft:overworld"))
                .join().companion().id();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        for (int i = 0; i < 25; i++) {
            var a = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "goto", "p" + i)).join();
            store.transitionAction(a.cancelled("stopped"), 0).join();
        }
        assertEquals(10, store.foldSettledActions(Long.MAX_VALUE, 10).join().actions());
        assertEquals(10, store.foldSettledActions(Long.MAX_VALUE, 10).join().actions());
        assertEquals(5, store.foldSettledActions(Long.MAX_VALUE, 10).join().actions());
        assertEquals(0, store.foldSettledActions(Long.MAX_VALUE, 10).join().actions());
        assertTrue(store.checkActionLedger().join().agrees());
        assertThrows(IllegalArgumentException.class, () -> store.foldSettledActions(Long.MAX_VALUE, 0));
    }
}
