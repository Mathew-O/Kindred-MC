package dev.kindred.persistence;

import dev.kindred.core.record.WorldEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: reading the Record's events of a few kinds (as the knowledge web does at every start) gives exactly
 * those events, in the Record's order, page after page, and SQLite finds them on the kind's index rather than walking
 * every event of a month's Record, with or without its statistics (F-32c).
 */
class WorldEventsByKindStoreTest {
    @TempDir Path directory;
    private final List<SqliteCompanionStore> opened = new ArrayList<>();

    @AfterEach
    void closeStores() {
        for (SqliteCompanionStore store : opened) store.closeAsync().join();
    }

    private static WorldEvent event(String kind, int n) {
        return WorldEvent.recorded(n, 1_000L + n, "minecraft:overworld", kind, UUID.randomUUID(), "s" + (n % 7), "", Map.of("n", String.valueOf(n)));
    }

    @Test
    void theKindsAskedForComeBackWholeInTheRecordsOrderAcrossPages() {
        SqliteCompanionStore store = SqliteCompanionStore.open(directory.resolve("kinds.sqlite"), 10).join();
        opened.add(store);
        // 40,000 events: a "message" every 4th, a "claim" every 10th, the rest journal and action, interleaved.
        var batch = new ArrayList<WorldEvent>();
        for (int n = 0; n < 40_000; n++) {
            String kind = n % 4 == 0 ? "message" : n % 10 == 3 ? "claim" : n % 2 == 0 ? "journal" : "action";
            batch.add(event(kind, n));
            if (batch.size() == 2_000) { store.record(List.copyOf(batch)).join(); batch.clear(); }
        }
        var every = store.worldEvents(Set.of(), 0, 10_000).join();
        assertEquals(10_000, every.size(), "every kind, the first page");
        var wanted = Set.of("message", "claim", "capture");

        var all = store.allWorldEvents(wanted).join();
        assertEquals(10_000 + 4_000, all.size(), "every message and claim, past the 5,000-event pages");
        assertTrue(all.stream().allMatch(e -> wanted.contains(e.kind())), "only the kinds asked for");
        for (int i = 1; i < all.size(); i++) assertTrue(all.get(i).id() > all.get(i - 1).id(), "in the Record's order at " + i);
        assertEquals(all.stream().map(e -> e.data().get("n")).toList(),
                java.util.stream.IntStream.range(0, 40_000).filter(n -> n % 4 == 0 || n % 10 == 3).mapToObj(String::valueOf).toList(),
                "the same events as the Record holds them");

        // A page after an id: the next events of those kinds, at most the limit, in order.
        long after = all.get(2_499).id();
        var page = store.worldEvents(wanted, after, 1_000).join();
        assertEquals(all.subList(2_500, 3_500).stream().map(WorldEvent::id).toList(), page.stream().map(WorldEvent::id).toList());
        assertTrue(store.worldEvents(Set.of("capture"), 0, 10).join().isEmpty(), "a kind with none: none");
    }

    @Test
    void theWebsReadSearchesTheKindIndexNotEveryEventEvenWithStatistics() throws Exception {
        Path file = directory.resolve("plan.sqlite");
        SqliteCompanionStore store = SqliteCompanionStore.open(file, 10).join();
        opened.add(store);
        var batch = new ArrayList<WorldEvent>();
        for (int n = 0; n < 20_000; n++) batch.add(event(n % 500 == 0 ? "message" : n % 2 == 0 ? "journal" : "action", n));
        store.record(batch).join();
        var webKinds = Set.of("claim", "unclaim", "capture", "correction", "message");
        String plan = store.worldEventsPlanForTest(webKinds).join();
        assertTrue(plan.contains("USING INDEX world_events_by_kind"), "on the kind's index: " + plan);
        assertFalse(plan.contains("INTEGER PRIMARY KEY") || plan.contains("SCAN world_events"), "never every event: " + plan);
        // The same once SQLite has statistics (an operator's ANALYZE, or a later PRAGMA optimize).
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file)) { c.createStatement().execute("ANALYZE"); }
        store.closeAsync().join();
        opened.remove(store);
        store = SqliteCompanionStore.open(file, 10).join();
        opened.add(store);
        String analysed = store.worldEventsPlanForTest(webKinds).join();
        assertTrue(analysed.contains("USING INDEX world_events_by_kind"), "with statistics, still the kind's index: " + analysed);
    }
}
