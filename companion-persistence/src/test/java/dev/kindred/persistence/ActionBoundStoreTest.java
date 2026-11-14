package dev.kindred.persistence;

import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.record.ActionLedgerView;
import dev.kindred.core.record.WorldEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the action bound (F-31b1, Session A's decision of 2026-11-14): a settled action folded long ago
 * is pruned, its row and its summary together, once it is more than 30 game days old on the fold's clock, and a tally of
 * what was pruned is kept for each game day; an action still unresolved, or ever uncertain, is kept whatever its age; a
 * pass whose agreement check did not agree prunes nothing; the ledger still agrees with the World Record, the unresolved
 * actions a restart, a settle and a status read are as they were, before and after a reopen; and every other kind of
 * event still refuses a delete.
 */
class ActionBoundStoreTest {
    private static final long BOUND = 30 * SqliteCompanionStore.GAME_DAY_MS;

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

    private record Staged(UUID companion, ActionRecord verified, ActionRecord failed, ActionRecord dispatched, ActionRecord prepared,
                          ActionRecord wasUncertain) { }

    /** Two settled actions, one dispatched and one prepared (unresolved), and one settled after being uncertain; all folded that can be. */
    private Staged stage(SqliteCompanionStore store) {
        UUID id = store.create(new CreationRequest(UUID.randomUUID(), UUID.randomUUID(), "Juniper", UUID.randomUUID(), "minecraft:overworld"))
                .join().companion().id();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        var verified = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "deposit", "chest")).join();
        verified = store.transitionAction(store.transitionAction(verified.dispatched("8 logs"), 0).join().verified("chest 8", "moved"), 1).join();
        var failed = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "goto", "x")).join();
        failed = store.transitionAction(store.transitionAction(failed.dispatched("here"), 0).join().failed("there", "no route"), 1).join();
        var dispatched = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "mine", "z")).join();
        dispatched = store.transitionAction(dispatched.dispatched("ore"), 0).join();
        var prepared = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "craft", "w")).join();
        var wasUncertain = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "native_break", "minecraft:stone at 1, 2, 3")).join();
        wasUncertain = store.transitionAction(wasUncertain.dispatched("before"), 0).join();
        wasUncertain = store.transitionAction(wasUncertain.uncertain("ended mid-way"), 1).join();
        wasUncertain = store.transitionAction(wasUncertain.reconciled(false, "settlement: still there", "Settled not done"), 2).join();
        var fold = store.foldSettledActions(Long.MAX_VALUE, 5_000).join();
        assertEquals(2, fold.actions(), "the verified and failed actions fold; the one ever uncertain does not");
        return new Staged(id, verified, failed, dispatched, prepared, wasUncertain);
    }

    private static long summaryTime(SqliteCompanionStore store, UUID action) {
        return store.worldEvents(Set.of(ActionLedgerView.FOLDED), 0, 100).join().stream()
                .filter(e -> e.subject().equals(action.toString())).findFirst().orElseThrow().realTimeMs();
    }

    private static Set<UUID> rows(SqliteCompanionStore store) {
        return store.checkActionLedger().join().kept().keySet();
    }

    @Test
    void aSettledActionIsKeptUpToThirtyGameDaysAndPrunedWithItsSummaryJustPastThem() {
        SqliteCompanionStore store = open();
        var staged = stage(store);
        long tv = summaryTime(store, staged.verified().actionId()), tf = summaryTime(store, staged.failed().actionId());
        long earliest = Math.min(tv, tf), latest = Math.max(tv, tf);
        var check = store.checkActionLedgerFromSnapshot().join();
        assertTrue(check.agrees(), "the pass's check agreed: " + check);

        // 30 game days less a millisecond after the earlier one's last event: both kept, rows and summaries.
        var early = store.pruneSettledActions(check, (earliest + BOUND - 1) - BOUND, 5_000).join();
        assertEquals(0, early.actions());
        assertTrue(rows(store).contains(staged.verified().actionId()) && rows(store).contains(staged.failed().actionId()));
        assertEquals(2, store.worldEvents(Set.of(ActionLedgerView.FOLDED), 0, 100).join().size());

        // A millisecond past 30 game days after the later one's: both pruned with their summaries, one tally for their day.
        var pruned = store.pruneSettledActions(check, (latest + BOUND + 1) - BOUND, 5_000).join();
        assertEquals(2, pruned.actions());
        assertEquals(1, pruned.days());
        assertFalse(rows(store).contains(staged.verified().actionId()));
        assertFalse(rows(store).contains(staged.failed().actionId()));
        assertTrue(store.worldEvents(Set.of(ActionLedgerView.FOLDED), 0, 100).join().isEmpty(), "both summaries pruned");
        var tallies = store.worldEvents(Set.of(ActionLedgerView.PRUNED), 0, 100).join();
        assertEquals(1, tallies.size());
        var tally = tallies.getFirst();
        assertEquals("2", tally.get("total", ""));
        assertEquals("1", tally.get("verified", ""));
        assertEquals("1", tally.get("failed", ""));
        var ends = Set.of(tally.get("first", ""), tally.get("last", ""));
        assertEquals(Set.of(staged.verified().actionId().toString(), staged.failed().actionId().toString()), ends, "the first and last of the day");
        long day = Long.parseLong(tally.subject());
        assertEquals(String.valueOf(day * SqliteCompanionStore.GAME_DAY_MS), tally.get("day_start_ms", ""));

        // A second pass finds nothing more.
        assertEquals(0, store.pruneSettledActions(check, Long.MAX_VALUE, 5_000).join().actions());
    }

    @Test
    void unresolvedAndEverUncertainActionsAreKeptWhateverTheirAge() {
        SqliteCompanionStore store = open();
        var staged = stage(store);
        var check = store.checkActionLedgerFromSnapshot().join();
        store.pruneSettledActions(check, Long.MAX_VALUE, 5_000).join();
        var left = rows(store);
        assertTrue(left.contains(staged.dispatched().actionId()), "an unresolved, dispatched action, however old");
        assertTrue(left.contains(staged.prepared().actionId()), "an unresolved, prepared action, however old");
        assertTrue(left.contains(staged.wasUncertain().actionId()), "an action once uncertain, though settled since");
        assertEquals(3, left.size());
        var history = store.worldEvents(Set.of(ActionLedgerView.ACTION), 0, 100).join();
        assertEquals(4, history.stream().filter(e -> e.subject().equals(staged.wasUncertain().actionId().toString())).count(),
                "its whole history kept");
    }

    @Test
    void aPassWhoseCheckDidNotAgreePrunesNothing() {
        SqliteCompanionStore store = open();
        var staged = stage(store);
        var differs = new SqliteCompanionStore.LedgerCheck(false, 5, 9, List.of("one action differs"), 1, List.of(), 0);
        var none = store.pruneSettledActions(differs, Long.MAX_VALUE, 5_000).join();
        assertEquals(0, none.actions());
        assertEquals(5, rows(store).size(), "every row kept");
        assertEquals(2, store.worldEvents(Set.of(ActionLedgerView.FOLDED), 0, 100).join().size(), "every summary kept");
        assertTrue(store.worldEvents(Set.of(ActionLedgerView.PRUNED), 0, 100).join().isEmpty(), "no tally");
        assertTrue(rows(store).contains(staged.verified().actionId()));
        assertEquals(0, store.pruneSettledActions(null, Long.MAX_VALUE, 5_000).join().actions(), "nor with no check at all");
    }

    @Test
    void afterAPruneTheLedgerAgreesAndWhatARestartASettleAndAStatusReadIsUnchanged() throws Exception {
        SqliteCompanionStore store = open();
        var staged = stage(store);
        var unresolvedBefore = store.unresolvedActions(staged.companion()).join();
        var eventsBefore = store.eventsSince(staged.companion(), 0, 40).join();
        store.pruneSettledActions(store.checkActionLedgerFromSnapshot().join(), Long.MAX_VALUE, 5_000).join();

        assertTrue(store.checkActionLedger().join().agrees(), "the ledger rebuilt from the Record is the table");
        assertTrue(store.checkActionLedgerFromSnapshot().join().agrees(), "and from a snapshot");
        assertEquals(unresolvedBefore, store.unresolvedActions(staged.companion()).join(), "a settle and a status read the same unresolved actions");
        assertEquals(eventsBefore, store.eventsSince(staged.companion(), 0, 40).join(), "a status reads the same journal");
        store.closeAsync().join();

        var reopened = open();
        assertTrue(reopened.checkActionLedger().join().agrees(), "agrees after a reopen");
        assertEquals(unresolvedBefore.stream().map(ActionRecord::actionId).toList(),
                reopened.unresolvedActions(staged.companion()).join().stream().map(ActionRecord::actionId).toList(),
                "a restart finds the same unresolved actions");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("companions.sqlite")); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("PRAGMA user_version")) { assertEquals(14, row.getInt(1)); }
        }
    }

    @Test
    void aSummaryWhoseRowIsStillThereAndEveryOtherKindStillRefuseADelete() throws Exception {
        SqliteCompanionStore store = open();
        stage(store);
        store.record(WorldEvent.recorded(5, 9, "w", "claim", null, "red", "w:0:0", Map.of("by", "red"))).join();
        store.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("companions.sqlite")); var statement = connection.createStatement()) {
            var refused = assertThrows(java.sql.SQLException.class, () -> statement.execute("DELETE FROM world_events WHERE kind = '" + ActionLedgerView.FOLDED + "'"),
                    "a summary whose row is still in the ledger");
            assertTrue(refused.getMessage().contains("The World Record is append-only"), refused.getMessage());
        }
        var reopened = open();
        reopened.pruneSettledActions(reopened.checkActionLedgerFromSnapshot().join(), Long.MAX_VALUE, 5_000).join();
        reopened.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("companions.sqlite")); var statement = connection.createStatement()) {
            try (var count = statement.executeQuery("SELECT COUNT(*) FROM world_events WHERE kind IN ('claim', '" + ActionLedgerView.PRUNED + "', 'journal')")) {
                assertTrue(count.getInt(1) >= 2, "a claim and a tally there to refuse");
            }
            for (String kind : List.of("claim", ActionLedgerView.PRUNED)) {
                var refused = assertThrows(java.sql.SQLException.class, () -> statement.execute("DELETE FROM world_events WHERE kind = '" + kind + "'"), kind);
                assertTrue(refused.getMessage().contains("The World Record is append-only"), refused.getMessage());
            }
            var update = assertThrows(java.sql.SQLException.class, () -> statement.execute("UPDATE world_events SET subject = 'x' WHERE kind = 'claim'"), "an update of any kind");
            assertTrue(update.getMessage().contains("The World Record is append-only"), update.getMessage());
        }
    }

    @Test
    void aDayPrunedOverTwoPassesSumsToTheSameAsInOneAndNothingNewerThanTheCutIsTouched() {
        // Two stores alike: one pruned in one pass, one in passes of one action each (the max remainder left to the next).
        SqliteCompanionStore store = open();
        var staged = stage(store);
        var check = store.checkActionLedgerFromSnapshot().join();
        long tv = summaryTime(store, staged.verified().actionId()), tf = summaryTime(store, staged.failed().actionId());

        // The clock jumped back: a cut before both summaries touches neither.
        assertEquals(0, store.pruneSettledActions(check, Math.min(tv, tf), 1).join().actions(), "nothing newer than the cut");
        assertEquals(2, store.worldEvents(Set.of(ActionLedgerView.FOLDED), 0, 100).join().size());

        var first = store.pruneSettledActions(check, Long.MAX_VALUE, 1).join();
        assertEquals(1, first.actions(), "a pass takes at most its max");
        var second = store.pruneSettledActions(check, Long.MAX_VALUE, 1).join();
        assertEquals(1, second.actions(), "the next pass takes the rest");
        assertEquals(0, store.pruneSettledActions(check, Long.MAX_VALUE, 1).join().actions(), "and then nothing");

        var tallies = store.worldEvents(Set.of(ActionLedgerView.PRUNED), 0, 100).join();
        assertEquals(2, tallies.size(), "one tally a pass for the day");
        long total = 0, verified = 0, failed = 0;
        var named = new java.util.HashSet<String>();
        for (var tally : tallies) {
            assertEquals(tallies.getFirst().subject(), tally.subject(), "the same game day");
            total += Long.parseLong(tally.get("total", "0"));
            verified += Long.parseLong(tally.get("verified", "0"));
            failed += Long.parseLong(tally.get("failed", "0"));
            named.add(tally.get("first", ""));
            named.add(tally.get("last", ""));
        }
        assertEquals(2, total, "the day's figures are the sum of its tallies, nothing counted twice");
        assertEquals(1, verified);
        assertEquals(1, failed);
        assertEquals(Set.of(staged.verified().actionId().toString(), staged.failed().actionId().toString()), named);

        // As in one pass: the same total for the same day.
        SqliteCompanionStore other = SqliteCompanionStore.open(directory.resolve("other.sqlite"), 10).join();
        opened.add(other);
        stage(other);
        other.pruneSettledActions(other.checkActionLedgerFromSnapshot().join(), Long.MAX_VALUE, 5_000).join();
        var once = other.worldEvents(Set.of(ActionLedgerView.PRUNED), 0, 100).join();
        assertEquals(1, once.size());
        assertEquals(String.valueOf(total), once.getFirst().get("total", ""));
        assertTrue(store.checkActionLedger().join().agrees() && other.checkActionLedger().join().agrees());
    }
}
