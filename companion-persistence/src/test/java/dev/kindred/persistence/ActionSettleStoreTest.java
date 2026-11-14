package dev.kindred.persistence;

import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.ActionSettlement;
import dev.kindred.core.record.ActionLedgerView;
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
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: an action a crash left without an outcome (F-31g1) is settled once, done or not done, in one write
 * with the Record's note of who settled it and what was seen; a second settlement, or one on an action that is not
 * uncertain, is refused and writes nothing; the ledger still agrees with the Record after.
 */
class ActionSettleStoreTest {
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

    private static WorldEvent note(UUID player, ActionRecord action, String by) {
        return WorldEvent.recorded(-1, System.currentTimeMillis(), "", "action.settled", player, action.actionId().toString(), "",
                Map.of("by", by, "outcome", action.outcome().name(), "evidence", "minecraft:stone at 1, 2, 3: now minecraft:stone"));
    }

    @Test
    void anActionLeftDispatchedByACrashIsSettledOnceWithItsNote() {
        SqliteCompanionStore store = open();
        UUID id = store.create(new CreationRequest(UUID.randomUUID(), UUID.randomUUID(), "Ada", UUID.randomUUID(), "minecraft:overworld"))
                .join().companion().id();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        var dig = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "native_break", "minecraft:stone at 1, 2, 3")).join();
        dig = store.transitionAction(dig.dispatched("native owned inventory slots=41; sha256=" + "a".repeat(64)), 0).join();
        var other = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "native_break", "minecraft:dirt at 4, 5, 6")).join();
        other = store.transitionAction(other.dispatched("native owned inventory slots=41; sha256=" + "b".repeat(64)), 0).join();
        // The process ends with both dispatched: the next open marks them uncertain, as after the kill.
        store.closeAsync().join();
        opened.clear();

        var reopened = open();
        String digId = dig.actionId().toString();
        var uncertain = reopened.findAction(dig.actionId()).join().orElseThrow();
        assertEquals(ActionRecord.State.UNCERTAIN, uncertain.state());
        var settled = ActionSettlement.settled(uncertain, false, "minecraft:stone at 1, 2, 3: now minecraft:stone, still there", "console (operator)");
        var written = reopened.settleAction(settled, uncertain.revision(), note(id, settled, "console (operator)")).join();
        assertEquals(ActionRecord.State.FAILED, written.state());
        assertEquals(ActionRecord.State.FAILED, reopened.findAction(dig.actionId()).join().orElseThrow().state());
        assertEquals(List.of(other.actionId()), reopened.unresolvedActions(id).join().stream().map(ActionRecord::actionId).toList(),
                "Only the other dig still unresolved");
        var notes = reopened.worldEvents(Set.of("action.settled"), 0, 10).join();
        assertEquals(1, notes.size());
        assertEquals("console (operator)", notes.get(0).data().get("by"));
        assertEquals(dig.actionId().toString(), notes.get(0).subject());
        assertTrue(reopened.checkActionLedger().join().agrees(), "The ledger agrees with the Record");

        // Settled again, or with the revision it had before: refused, and nothing more written.
        var again = ActionSettlement.settled(uncertain, true, "x", "console");
        var refused = assertThrows(CompletionException.class, () -> reopened.settleAction(again, uncertain.revision(), note(id, again, "console")).join());
        assertInstanceOf(IllegalStateException.class, refused.getCause());
        var otherUncertain = reopened.findAction(other.actionId()).join().orElseThrow();
        var stale = ActionSettlement.settled(otherUncertain, true, "y", "console");
        assertThrows(CompletionException.class, () -> reopened.settleAction(stale, otherUncertain.revision() - 1, note(id, stale, "console")).join());
        assertEquals(ActionRecord.State.UNCERTAIN, reopened.findAction(other.actionId()).join().orElseThrow().state());
        assertEquals(1, reopened.worldEvents(Set.of("action.settled"), 0, 10).join().size());
        assertEquals(ActionRecord.State.FAILED, reopened.findAction(dig.actionId()).join().orElseThrow().state());
        assertEquals(1, reopened.worldEvents(Set.of(ActionLedgerView.ACTION), 0, 100).join().stream()
                .filter(e -> e.subject().equals(digId) && "FAILED".equals(e.data().get("state"))).count());
    }

    @Test
    void aSettlementIsDoneOrNotDoneNeverAnythingElse() {
        SqliteCompanionStore store = open();
        UUID id = store.create(new CreationRequest(UUID.randomUUID(), UUID.randomUUID(), "Bram", UUID.randomUUID(), "minecraft:overworld"))
                .join().companion().id();
        var lease = new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        var dig = store.prepareAction(ActionRecord.prepare(UUID.randomUUID(), lease, "native_break", "minecraft:stone at 1, 2, 3")).join();
        var dispatched = dig.dispatched("before");
        assertThrows(IllegalArgumentException.class, () -> store.settleAction(dispatched, 0, note(id, dispatched, "console")));
    }
}
