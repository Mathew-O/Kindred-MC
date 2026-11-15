package dev.kindred.persistence;

import dev.kindred.core.record.TerritoryRefusals;
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
 * In plain terms: checks the refusals' bound (F-34c2a, with Session A's agreement of 2026-11-15): a refused attempt on a
 * faction's land is kept thirty game days by its own time and pruned past them, with a tally of what was pruned kept
 * for each game day; one inside the bound survives a prune, and the database itself refuses to delete it; a pass whose
 * check did not agree prunes nothing; a day pruned over two passes sums to the same as in one; every other kind still
 * refuses a delete; and a newer schema is refused.
 */
class TerritoryRefusalBoundStoreTest {
    private static final long BOUND = SqliteCompanionStore.REFUSAL_BOUND_MS;
    private static final SqliteCompanionStore.LedgerCheck AGREES = new SqliteCompanionStore.LedgerCheck(true, 0, 0, List.of(), 0, List.of(), 0);

    @TempDir Path directory;
    private final List<SqliteCompanionStore> opened = new ArrayList<>();

    @AfterEach
    void closeStores() {
        for (SqliteCompanionStore store : opened) store.closeAsync().join();
    }

    private SqliteCompanionStore open(String name) {
        SqliteCompanionStore store = SqliteCompanionStore.open(directory.resolve(name), 10).join();
        opened.add(store);
        return store;
    }

    private static WorldEvent refused(long at, String what, String chunk) {
        return WorldEvent.recorded(1, at, "minecraft:overworld", TerritoryRefusals.REFUSED, UUID.randomUUID(), "red", chunk, Map.of("what", what));
    }

    private static List<WorldEvent> of(SqliteCompanionStore store, String kind) {
        return store.worldEvents(Set.of(kind), 0, 1000).join();
    }

    /** Two past the bound on one game day, one past it on the day before, one just inside it. */
    private static long stage(SqliteCompanionStore store, long now) {
        long cut = now - BOUND;
        long dayStart = Math.floorDiv(cut, SqliteCompanionStore.GAME_DAY_MS) * SqliteCompanionStore.GAME_DAY_MS;
        store.record(List.of(
                refused(dayStart + 1, "break", "w:0:0"),
                refused(dayStart + 2, "hit", "w:0:1"),
                refused(dayStart - SqliteCompanionStore.GAME_DAY_MS + 5, "explode", "w:1:0"),
                refused(cut + 60_000, "break", "w:0:0"))).join();
        return cut;
    }

    @Test
    void aRefusalIsKeptInsideTheBoundAndPrunedPastItWithADaysTally() {
        SqliteCompanionStore store = open("a.sqlite");
        long now = System.currentTimeMillis();
        long cut = stage(store, now);
        var prune = store.pruneTerritoryRefusals(AGREES, cut, 5_000).join();
        assertEquals(3, prune.refusals(), "the three past the bound");
        assertEquals(2, prune.days(), "one tally for each of their two game days");
        var left = of(store, TerritoryRefusals.REFUSED);
        assertEquals(1, left.size(), "the one inside the bound survives");
        assertTrue(left.getFirst().realTimeMs() > cut);
        var tallies = of(store, TerritoryRefusals.PRUNED);
        assertEquals(2, tallies.size());
        var later = tallies.stream().filter(t -> t.get("total", "").equals("2")).findFirst().orElseThrow();
        assertEquals("1", later.get("break", ""));
        assertEquals("1", later.get("hit", ""));
        assertEquals(String.valueOf(cut), later.get("older_than_ms", ""));
        var earlier = tallies.stream().filter(t -> t.get("total", "").equals("1")).findFirst().orElseThrow();
        assertEquals("1", earlier.get("explode", ""));
        assertTrue(Long.parseLong(earlier.get("first", "0")) > 0 && earlier.get("first", "").equals(earlier.get("last", "")), "its one refusal both first and last");
        assertEquals(0, store.pruneTerritoryRefusals(AGREES, cut, 5_000).join().refusals(), "nothing left past the bound");
    }

    @Test
    void aPassWhoseCheckDidNotAgreePrunesNothing() {
        SqliteCompanionStore store = open("b.sqlite");
        long cut = stage(store, System.currentTimeMillis());
        var differs = new SqliteCompanionStore.LedgerCheck(false, 5, 9, List.of("one row differs"), 1, List.of(), 0);
        assertEquals(0, store.pruneTerritoryRefusals(differs, cut, 5_000).join().refusals());
        assertEquals(0, store.pruneTerritoryRefusals(null, cut, 5_000).join().refusals(), "nor with no check at all");
        assertEquals(4, of(store, TerritoryRefusals.REFUSED).size(), "every refusal kept");
        assertTrue(of(store, TerritoryRefusals.PRUNED).isEmpty(), "no tally");
    }

    @Test
    void theDatabaseRefusesARefusalsDeleteInsideTheBoundAndAPassCutThereKeepsEverything() throws Exception {
        SqliteCompanionStore store = open("c.sqlite");
        long now = System.currentTimeMillis();
        stage(store, now);
        // Asked to cut past now, the pass meets the one inside the bound: the database refuses, the transaction keeps all.
        assertThrows(Exception.class, () -> store.pruneTerritoryRefusals(AGREES, Long.MAX_VALUE, 5_000).join());
        assertEquals(4, of(store, TerritoryRefusals.REFUSED).size(), "nothing pruned by a pass that failed");
        assertTrue(of(store, TerritoryRefusals.PRUNED).isEmpty(), "and no tally");
        store.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("c.sqlite")); var statement = connection.createStatement()) {
            var refused = assertThrows(java.sql.SQLException.class,
                    () -> statement.execute("DELETE FROM world_events WHERE kind = '" + TerritoryRefusals.REFUSED + "' AND real_time_ms > " + (now - BOUND)),
                    "a refusal inside the bound");
            assertTrue(refused.getMessage().contains("The World Record is append-only"), refused.getMessage());
            // Past the bound, the database lets it go: the prune's own delete.
            assertEquals(3, statement.executeUpdate("DELETE FROM world_events WHERE kind = '" + TerritoryRefusals.REFUSED + "' AND real_time_ms < " + (now - BOUND)));
            try (var row = statement.executeQuery("PRAGMA user_version")) { assertEquals(15, row.getInt(1)); }
        }
    }

    @Test
    void everyOtherKindStillRefusesADeleteAndANewerSchemaIsRefused() throws Exception {
        SqliteCompanionStore store = open("d.sqlite");
        long cut = stage(store, System.currentTimeMillis());
        store.record(WorldEvent.recorded(5, 9, "w", "claim", null, "red", "w:0:0", Map.of("by", "red"))).join();
        store.pruneTerritoryRefusals(AGREES, cut, 5_000).join();
        store.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("d.sqlite")); var statement = connection.createStatement()) {
            for (String kind : List.of("claim", TerritoryRefusals.PRUNED)) {
                var refused = assertThrows(java.sql.SQLException.class, () -> statement.execute("DELETE FROM world_events WHERE kind = '" + kind + "'"), kind);
                assertTrue(refused.getMessage().contains("The World Record is append-only"), refused.getMessage());
            }
            var update = assertThrows(java.sql.SQLException.class,
                    () -> statement.execute("UPDATE world_events SET subject = 'x' WHERE kind = '" + TerritoryRefusals.REFUSED + "'"), "an update, even of a refusal");
            assertTrue(update.getMessage().contains("The World Record is append-only"), update.getMessage());
            statement.execute("PRAGMA user_version = 16");
        }
        assertThrows(Exception.class, () -> SqliteCompanionStore.open(directory.resolve("d.sqlite"), 10).join(), "a newer schema is refused, never downgraded");
    }

    @Test
    void aDayPrunedOverTwoPassesSumsToTheSameAsInOne() {
        long now = System.currentTimeMillis();
        SqliteCompanionStore once = open("e1.sqlite");
        SqliteCompanionStore inPasses = open("e2.sqlite");
        long cut = stage(once, now);
        stage(inPasses, now);
        once.pruneTerritoryRefusals(AGREES, cut, 5_000).join();
        int passes = 0;
        while (inPasses.pruneTerritoryRefusals(AGREES, cut, 1).join().refusals() == 1) passes++;
        assertEquals(3, passes, "one refusal a pass");
        assertEquals(sums(once), sums(inPasses), "each day's figures the sum of its tallies");
        assertEquals(1, of(inPasses, TerritoryRefusals.REFUSED).size(), "the one inside the bound untouched");
    }

    /** Each game day's tallies summed, by figure. */
    private static Map<String, Map<String, Long>> sums(SqliteCompanionStore store) {
        var out = new java.util.TreeMap<String, Map<String, Long>>();
        for (var tally : of(store, TerritoryRefusals.PRUNED)) {
            var day = out.computeIfAbsent(tally.get("day_start_ms", ""), d -> new java.util.TreeMap<>());
            for (String key : List.of("total", "break", "place", "explode", "mob_change", "hit")) {
                long n = Long.parseLong(tally.get(key, "0"));
                if (n > 0) day.merge(key, n, Long::sum);
            }
        }
        return out;
    }
}
