package dev.kindred.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.record.EventData;
import dev.kindred.core.record.WorldEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * In plain terms: checks the cost gauge's counts of the World Record: every event the store writes is counted by kind
 * with its size, the journal's and the action ledger's included (which the store writes itself, in their own rows'
 * transactions), and the counts agree with the rows the Record then holds; after a reopen the counts start again from
 * nothing while the last id is the Record's.
 */
class RecordGrowthTest {
    @TempDir Path directory;
    private final List<SqliteCompanionStore> opened = new ArrayList<>();

    @AfterEach
    void closeStores() {
        for (SqliteCompanionStore store : opened) store.closeAsync().join();
    }

    @Test
    void everyEventTheStoreWritesIsCountedByKindTheJournalAndTheActionLedgerIncluded() {
        SqliteCompanionStore store = open();
        UUID id = store.create(request()).join().companion().id();
        store.record(List.of(
                WorldEvent.recorded(10, 1_000, "minecraft:overworld", "claim", null, "red", "minecraft:overworld:3:-4", Map.of()),
                WorldEvent.recorded(11, 1_050, "minecraft:overworld", "death", id, "", "12,64,-50", Map.of("cause", "lava")))).join();
        store.appendEvent(id, Instant.ofEpochMilli(5_000), "WORK_VERIFIED", "a & b = c").join();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        ActionRecord prepared = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "deposit", "chest")).join();
        ActionRecord dispatched = store.transitionAction(prepared.dispatched("8 logs"), 0).join();
        store.transitionAction(dispatched.verified("chest 8", "moved"), 1).join();

        var all = store.allWorldEvents(Set.of()).join();
        var expected = new TreeMap<String, long[]>();
        for (WorldEvent e : all) {
            var k = expected.computeIfAbsent(e.kind(), x -> new long[2]);
            k[0]++;
            k[1] += EventData.encode(e.data()).length() + e.subject().length() + e.place().length();
        }
        var growth = store.growth();
        assertEquals(expected.keySet(), growth.byKind().keySet(), "the same kinds as the Record holds");
        expected.forEach((kind, v) -> assertEquals(List.of(v[0], v[1]), List.of(growth.byKind().get(kind)[0], growth.byKind().get(kind)[1]),
                kind + ": rows and characters as written"));
        assertTrue(growth.byKind().get("journal")[0] >= 1, "the journal's line counted, written by the store itself");
        assertEquals(3, growth.byKind().get("action")[0], "each action-ledger row's event counted: prepared, dispatched, verified");
        assertEquals(1, growth.byKind().get("claim")[0]);
        assertEquals(all.size(), growth.rows());
        assertEquals(all.getLast().id(), growth.through(), "through the Record's last id");
    }

    @Test
    void afterAReopenTheCountsStartFromNothingAndTheLastIdIsTheRecords() {
        SqliteCompanionStore store = open();
        var written = store.record(WorldEvent.recorded(1, 1, "w", "claim", null, "red", "w:0:0", Map.of())).join();
        store.closeAsync().join();
        opened.remove(store);
        SqliteCompanionStore again = open();
        var growth = again.growth();
        assertEquals(0, growth.rows(), "nothing written since this open");
        assertTrue(growth.byKind().isEmpty());
        assertEquals(written.id(), growth.through(), "the Record's last id, read as it opens");
        var next = again.record(WorldEvent.recorded(2, 2, "w", "claim", null, "red", "w:1:0", Map.of())).join();
        assertEquals(1, again.growth().byKind().get("claim")[0]);
        assertEquals(next.id(), again.growth().through());
    }

    private SqliteCompanionStore open() {
        SqliteCompanionStore store = SqliteCompanionStore.open(directory.resolve("companions.sqlite"), 10).join();
        opened.add(store);
        return store;
    }

    private static CreationRequest request() {
        return new CreationRequest(UUID.randomUUID(), UUID.randomUUID(), "Juniper", UUID.randomUUID(), "minecraft:overworld");
    }
}
