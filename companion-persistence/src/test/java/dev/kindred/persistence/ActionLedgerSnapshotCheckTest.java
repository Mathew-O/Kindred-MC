package dev.kindred.persistence;

import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.record.ActionLedgerView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: the action ledger's check read off the writer gives the same answer as the check the writer held,
 * whatever the store holds: a clean store; a row changed behind the store's back; an event with no row; malformed events;
 * history folded and not. It never writes. Stopped, it gives no answer at all, never a pass.
 *
 * Technical: F-31f, {@link SqliteCompanionStore#checkActionLedgerFromSnapshot} against {@link SqliteCompanionStore#checkActionLedger}.
 */
class ActionLedgerSnapshotCheckTest {
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

    private void close(SqliteCompanionStore store) {
        store.closeAsync().join();
        opened.remove(store);
    }

    /** Settled and unsettled actions, as play leaves them: verified, failed, cancelled, still dispatched, uncertain. */
    private static List<UUID> actions(SqliteCompanionStore store, int each) {
        UUID id = store.create(new CreationRequest(UUID.randomUUID(), UUID.randomUUID(), "Juniper", UUID.randomUUID(), "minecraft:overworld"))
                .join().companion().id();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < each; i++) {
            var verified = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "deposit", "chest " + i)).join();
            store.transitionAction(store.transitionAction(verified.dispatched("8 logs"), 0).join().verified("chest 8", "moved"), 1).join();
            var failed = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "goto", "x " + i)).join();
            store.transitionAction(store.transitionAction(failed.dispatched("here"), 0).join().failed("there", "no route"), 1).join();
            var cancelled = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "toss", "y " + i)).join();
            store.transitionAction(cancelled.cancelled("stopped"), 0).join();
            var live = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "mine", "z " + i)).join();
            store.transitionAction(live.dispatched("ore"), 0).join();
            var uncertain = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "craft", "w " + i)).join();
            store.transitionAction(uncertain.uncertain("ended mid-way"), 0).join();
            ids.add(verified.actionId());
        }
        return ids;
    }

    private void sql(String... statements) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("companions.sqlite")); var statement = connection.createStatement()) {
            for (String each : statements) statement.execute(each);
        }
    }

    private static String event(String kind, String subject, String data) {
        return "INSERT INTO world_events(tick, real_time_ms, world, kind, actor, subject, place, data, how_known) VALUES (-1, 1, '', '"
                + kind + "', NULL, '" + subject + "', '', '" + data + "', 'recorded')";
    }

    /** Both checks on the store as it stands: the same verdict, and as many differences as the in-memory check finds. */
    private static SqliteCompanionStore.LedgerCheck same(SqliteCompanionStore store, String why) {
        var held = store.checkActionLedger().join();
        var streamed = store.checkActionLedgerFromSnapshot().join();
        assertEquals(held.agrees(), streamed.agrees(), why + ": " + streamed);
        var keys = new java.util.HashSet<UUID>(held.kept().keySet());
        keys.addAll(held.fromRecord().keySet());
        long differing = keys.stream().filter(k -> !java.util.Objects.equals(held.kept().get(k), held.fromRecord().get(k))).count();
        assertEquals(differing, streamed.differing(), why + ": " + streamed);
        assertEquals(held.kept().size(), streamed.rows(), why + ": rows");
        return streamed;
    }

    @Test
    void readOffTheWriterItGivesTheSameVerdictInEveryCase() throws Exception {
        var store = open();
        assertTrue(same(store, "an empty store").agrees());
        var ids = actions(store, 6);
        var clean = same(store, "a clean store");
        assertTrue(clean.agrees());
        assertEquals(30, clean.rows());
        assertTrue(clean.events() >= 30 * 2, "Every action's events read: " + clean.events());
        // The fold boundary: some actions folded into one event, the rest whole.
        assertTrue(store.foldSettledActions(Long.MAX_VALUE, 7).join().actions() > 0);
        assertTrue(same(store, "folded and unfolded").agrees());
        close(store);

        // A row changed behind the store's back.
        sql("UPDATE action_ledger SET state = 'VERIFIED', revision = revision + 5 WHERE action_id = '" + ids.get(2) + "'");
        store = open();
        var changed = same(store, "a row changed without its event");
        assertFalse(changed.agrees());
        assertEquals(1, changed.differing());
        assertTrue(changed.differences().getFirst().startsWith(ids.get(2).toString()), changed.differences().toString());
        close(store);
        sql("UPDATE action_ledger SET state = 'VERIFIED', revision = revision - 5 WHERE action_id = '" + ids.get(2) + "'");

        // Malformed events: no id, no revision, a revision that is no number. Each changes nothing.
        sql(event(ActionLedgerView.ACTION, "not an id", "state=VERIFIED&revision=1"),
                event(ActionLedgerView.ACTION, ids.get(0).toString(), "state=FAILED"),
                event(ActionLedgerView.FOLDED, ids.get(1).toString(), "state=FAILED&revision=many"));
        store = open();
        assertTrue(same(store, "malformed events").agrees());
        close(store);

        // An event with no row; a row with no event.
        UUID stray = UUID.randomUUID();
        sql(event(ActionLedgerView.ACTION, stray.toString(), "state=DISPATCHED&revision=0"));
        store = open();
        var unkept = same(store, "an event with no row");
        assertFalse(unkept.agrees());
        assertTrue(unkept.differences().toString().contains(stray + ": kept nothing"), unkept.differences().toString());
        close(store);
        UUID lone = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("companions.sqlite"))) {
            insertLike(connection, lone);
        }
        store = open();
        var unrecorded = same(store, "a row with no event, and an event with no row");
        assertFalse(unrecorded.agrees());
        assertEquals(2, unrecorded.differing());
        close(store);

        // An action named otherwise than as its id is written (upper case): read as the in-memory check reads it.
        sql(event(ActionLedgerView.ACTION, ids.get(3).toString().toUpperCase(), "state=CANCELLED&revision=9"));
        store = open();
        same(store, "an id written another way");
    }

    /** A copy of a real row under another id, column by column. */
    private static void insertLike(java.sql.Connection connection, UUID id) throws Exception {
        var columns = new ArrayList<String>();
        try (var info = connection.createStatement().executeQuery("PRAGMA table_info(action_ledger)")) {
            while (info.next()) columns.add(info.getString("name"));
        }
        String list = String.join(", ", columns);
        String select = String.join(", ", columns.stream().map(c -> c.equals("action_id") ? "?" : c).toList());
        try (var copy = connection.prepareStatement("INSERT INTO action_ledger(" + list + ") SELECT " + select + " FROM action_ledger LIMIT 1")) {
            copy.setString(1, id.toString());
            copy.execute();
        }
    }

    @Test
    void itNeverWritesAndStoppedItGivesNoVerdict() throws Exception {
        var store = open();
        actions(store, 40);
        // Its connection is read-only: the store's own writes go on while it is open, and it holds nothing of them.
        var check = store.checkActionLedgerFromSnapshot();
        store.stopChecks();
        try {
            var verdict = check.join();
            // It finished before the stop: a verdict, as any other.
            assertTrue(verdict.agrees());
        } catch (CancellationException stopped) {
            // Stopped: no verdict at all.
        }
        assertThrows(CancellationException.class, () -> store.checkActionLedgerFromSnapshot().join(), "None starts after a stop");
        assertTrue(store.checkActionLedger().join().agrees(), "The store itself is untouched");
    }
}
