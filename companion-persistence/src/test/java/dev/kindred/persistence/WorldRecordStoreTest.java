package dev.kindred.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.core.record.ActionLedgerView;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.DeathLedger;
import dev.kindred.core.record.Ledgers;
import dev.kindred.core.record.MatchLedger;
import dev.kindred.core.record.WorldEvent;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * In plain terms: checks the World Record (F-31): events are kept in order and never changed or deleted, the journal
 * and the action ledger are written there by their own calls, every ledger comes back the same from the Record alone
 * after a reopen, the action ledger's table and its events agree (and a tampered row is caught), and a schema 11
 * database gains the Record with its journal and actions already in it.
 */
class WorldRecordStoreTest {
    @TempDir Path directory;
    private final List<SqliteCompanionStore> opened = new ArrayList<>();

    @AfterEach
    void closeStores() {
        for (SqliteCompanionStore store : opened) store.closeAsync().join();
    }

    @Test
    void eventsAreKeptInOrderWithTheirDataAndReadByKind() {
        SqliteCompanionStore store = open();
        UUID actor = UUID.randomUUID();
        var written = store.record(List.of(
                WorldEvent.recorded(10, 1_000, "minecraft:overworld", "claim", null, "red", "minecraft:overworld:3:-4", Map.of()),
                WorldEvent.recorded(11, 1_050, "minecraft:overworld", "death", actor, "", "12,64,-50",
                        Map.of("cause", "lava & = % \n odd", "killer", "")))).join();
        assertEquals(2, written.size());
        assertTrue(written.get(0).id() > 0 && written.get(1).id() == written.get(0).id() + 1, "ids follow one another");
        assertEquals(written, store.allWorldEvents(Set.of()).join());
        assertEquals(List.of(written.get(1)), store.worldEvents(Set.of("death"), 0, 10).join());
        assertEquals(List.of(written.get(1)), store.worldEvents(Set.of(), written.get(0).id(), 10).join());
        assertThrows(IllegalArgumentException.class, () -> store.record(written.getFirst()), "a written event is never written again");
    }

    @Test
    void onlyARegisteredKindIsWrittenAndTheBatchIsRefusedWhole() {
        SqliteCompanionStore store = open();
        String kind = "test.catch." + Long.toHexString(System.nanoTime());
        var refused = store.record(List.of(
                WorldEvent.recorded(1, 1, "w", "claim", null, "red", "w:0:0", Map.of()),
                WorldEvent.recorded(2, 2, "w", kind, null, "", "", Map.of())));
        var failure = assertThrows(java.util.concurrent.CompletionException.class, refused::join);
        assertTrue(failure.getCause() instanceof IllegalArgumentException && failure.getCause().getMessage().contains(kind), "refused: " + failure);
        assertEquals(List.of(), store.allWorldEvents(Set.of()).join(), "all or none: nothing written");
        // Registered, as an integration registers its own kind, the same event is written.
        dev.kindred.core.record.EventKinds.register(kind, "test-integration");
        var written = store.record(WorldEvent.recorded(2, 2, "w", kind, null, "", "", Map.of())).join();
        assertEquals(List.of(written), store.worldEvents(Set.of(kind), 0, 10).join());
    }

    @Test
    void theRecordRefusesAnUpdateOrADelete() throws Exception {
        SqliteCompanionStore store = open();
        store.record(WorldEvent.recorded(1, 1, "w", "claim", null, "red", "w:0:0", Map.of())).join();
        store.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("UPDATE world_events SET subject = 'blue'"));
            assertThrows(SQLException.class, () -> statement.execute("DELETE FROM world_events"));
            try (var row = statement.executeQuery("SELECT subject FROM world_events")) { assertEquals("red", row.getString(1)); }
        }
    }

    @Test
    void theJournalAndTheActionLedgerAreKindsOfEventAndAgreeWithTheirTables() throws Exception {
        SqliteCompanionStore store = open();
        UUID id = store.create(request()).join().companion().id();
        store.appendEvent(id, Instant.ofEpochMilli(5_000), "WORK_VERIFIED", "a & b = c").join();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        ActionRecord prepared = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "deposit", "chest")).join();
        ActionRecord dispatched = store.transitionAction(prepared.dispatched("8 logs"), 0).join();
        store.transitionAction(dispatched.verified("chest 8", "moved"), 1).join();
        store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "pickup", "drop")).join();

        var journal = store.worldEvents(Set.of("journal"), 0, 100).join();
        assertEquals(2, journal.size(), "creation's own line, then this one");
        assertEquals("WORK_VERIFIED", journal.getLast().subject());
        assertEquals("a & b = c", journal.getLast().get("detail", ""));
        assertEquals(id, journal.getLast().actor());
        var actions = store.worldEvents(Set.of(ActionLedgerView.ACTION), 0, 100).join();
        assertEquals(List.of("PREPARED", "DISPATCHED", "VERIFIED", "PREPARED"), actions.stream().map(e -> e.get("state", "")).toList());
        var agreement = store.checkActionLedger().join();
        assertTrue(agreement.agrees(), "every row as its events give it: " + agreement);
        assertEquals(2, agreement.kept().size());
        store.closeAsync().join();

        // A row changed behind the writer's back is caught by the check.
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement()) {
            statement.execute("UPDATE action_ledger SET state = 'FAILED', outcome = 'FAILURE', revision = 9 WHERE action_id = '" + prepared.actionId() + "'");
        }
        var tampered = open().checkActionLedger().join();
        assertFalse(tampered.agrees());
        assertEquals(new ActionLedgerView.Row("VERIFIED", 2), tampered.fromRecord().get(prepared.actionId()));
    }

    @Test
    void everyLedgerIsRebuiltTheSameFromTheRecordAloneAfterAReopen() {
        SqliteCompanionStore store = open();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var registry = new MatchRegistry();
        var duel = registry.proposeDuel(List.of(a, b), 20 * 60).match().orElseThrow();
        registry.join(duel.id(), a);
        registry.join(duel.id(), b);
        registry.start(duel.id(), 100);
        registry.tick(160);
        registry.fallen(b);
        var events = new ArrayList<WorldEvent>();
        for (var changed : registry.drainChanges()) events.add(MatchLedger.event(160, 2_000, "w", changed));
        events.add(WorldEvent.recorded(160, 2_000, "w", DeathLedger.DEATH, b, "", "0,64,0", Map.of("cause", "player", "killer", a.toString())));
        events.add(WorldEvent.recorded(170, 2_100, "w", ClaimLedger.CLAIM, a, "red", ClaimLedger.chunk("w", 1, 1), Map.of()));
        events.add(WorldEvent.recorded(171, 2_100, "w", ClaimLedger.CLAIM, b, "blue", ClaimLedger.chunk("w", 5, 5), Map.of()));
        events.add(WorldEvent.recorded(180, 2_200, "w", ClaimLedger.CAPTURE, a, "red", ClaimLedger.chunk("w", 5, 5), Map.of("from", "blue")));
        Ledgers live = Ledgers.EMPTY;
        for (WorldEvent written : store.record(events).join()) live = live.apply(written);
        store.closeAsync().join();

        var reopened = open();
        var record = reopened.allWorldEvents(Ledgers.kinds()).join();
        Ledgers rebuilt = Ledgers.replay(record);
        assertEquals(live, rebuilt);
        assertTrue(rebuilt.agrees(record));
        assertEquals(a, rebuilt.matches().get(duel.id()).match().winner());
        assertEquals(Map.of(ClaimLedger.chunk("w", 1, 1), "red", ClaimLedger.chunk("w", 5, 5), "red"), rebuilt.claims());
        assertEquals(Map.of(b, 1), rebuilt.deaths());
    }

    @Test
    void aSchemaElevenDatabaseGainsTheRecordWithItsJournalAndActionsInIt() throws Exception {
        SqliteCompanionStore store = open();
        UUID id = store.create(request()).join().companion().id();
        store.appendEvent(id, Instant.ofEpochMilli(7_000), "NOTE", "50% & more =\nlines").join();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        ActionRecord prepared = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "deposit", "chest")).join();
        store.transitionAction(prepared.dispatched("8 logs"), 0).join();
        store.closeAsync().join();
        int journalRows;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE world_events");
            statement.execute("PRAGMA user_version = 11");
            try (var row = statement.executeQuery("SELECT COUNT(*) FROM journal_events")) { journalRows = row.getInt(1); }
        }
        var upgraded = open();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement();
             var row = statement.executeQuery("PRAGMA user_version")) {
            assertEquals(12, row.getInt(1));
        }
        var journal = upgraded.worldEvents(Set.of("journal"), 0, 1000).join();
        // Reopening reconciled the dispatched action: one journal line and one action event more than the backfill.
        assertEquals(journalRows + 1, journal.size());
        var note = journal.stream().filter(e -> e.subject().equals("NOTE")).findFirst().orElseThrow();
        assertEquals("50% & more =\nlines", note.get("detail", ""));
        assertEquals(id, note.actor());
        assertEquals(7_000, note.realTimeMs());
        var agreement = upgraded.checkActionLedger().join();
        assertTrue(agreement.agrees(), "the backfilled action ledger agrees with its table: " + agreement);
        assertEquals("UNCERTAIN", agreement.kept().get(prepared.actionId()).state());
        upgraded.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 13");
        }
        assertThrows(Exception.class, () -> SqliteCompanionStore.open(path(), 10).join(), "a newer schema is refused, never downgraded");
    }

    private SqliteCompanionStore open() {
        SqliteCompanionStore store = SqliteCompanionStore.open(path(), 10).join();
        opened.add(store);
        return store;
    }

    private Path path() { return directory.resolve("companions.sqlite"); }

    private static CreationRequest request() {
        return new CreationRequest(UUID.randomUUID(), UUID.randomUUID(), "Juniper", UUID.randomUUID(), "minecraft:overworld");
    }
}
