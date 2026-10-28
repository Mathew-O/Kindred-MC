package dev.kindred.persistence;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that action records are really written to the database, in order.
 *
 * Technical: Real ordered writer, with an explicit owner inbox instead of a Minecraft tick.
 */
class ActionAdmissionStoreTest {
    @TempDir Path directory;

    @Test void durableAdmissionAndVerificationMatchActualSqliteRows() throws Exception {
        var store = SqliteCompanionStore.open(directory.resolve("actions.sqlite"), 3).join();
        try {
            UUID id = create(store);
            ActionRecord intent = intent(id);
            BlockingQueue<Runnable> owner = new LinkedBlockingQueue<>();
            var admission = new ActionAdmission(intent, "native source 2 items", writer(store), owner::add);
            assertTrue(admission.take().isEmpty());
            pumpUntil(admission, ActionAdmission.Phase.READY, owner);
            ActionRecord issued = admission.take().orElseThrow();
            assertEquals(issued, store.findAction(intent.actionId()).join().orElseThrow());
            assertEquals(ActionRecord.State.DISPATCHED, issued.state());
            assertTrue(admission.take().isEmpty());
            var verified = admission.verify("native source 1, destination 1", "adapter observed exact transfer");
            pumpUntil(admission, ActionAdmission.Phase.VERIFIED, owner);
            assertEquals(verified.join(), store.findAction(intent.actionId()).join().orElseThrow());
            assertEquals(verified.join(), admission.close().join());
            assertTrue(store.unresolvedActions(id).join().isEmpty());
        } finally { store.closeAsync().join(); }
    }

    @Test void shutdownFlushesWithoutOwnerTicksAndReopenCannotReplayAnUncertainAction() throws Exception {
        Path path = directory.resolve("actions.sqlite");
        var store = SqliteCompanionStore.open(path, 3).join();
        UUID id = create(store);
        ActionRecord takenIntent = intent(id);
        try {
            BlockingQueue<Runnable> neverPumped = new LinkedBlockingQueue<>();
            var pending = new ActionAdmission(intent(id), "before", writer(store), neverPumped::add);
            ActionRecord cancelled = pending.close().join();
            assertEquals(ActionRecord.State.CANCELLED, cancelled.state());
            assertEquals(cancelled, store.findAction(cancelled.actionId()).join().orElseThrow());
            assertTrue(pending.take().isEmpty());
            BlockingQueue<Runnable> owner = new LinkedBlockingQueue<>();
            var unissued = new ActionAdmission(intent(id), "before", writer(store), owner::add);
            pumpUntil(unissued, ActionAdmission.Phase.READY, owner);
            assertEquals(ActionRecord.State.FAILED, unissued.close().join().state());
            var taken = new ActionAdmission(takenIntent, "before", writer(store), owner::add);
            pumpUntil(taken, ActionAdmission.Phase.READY, owner);
            assertTrue(taken.take().isPresent());
            ActionRecord uncertain = taken.close().join();
            assertEquals(ActionRecord.State.UNCERTAIN, uncertain.state());
            assertTrue(uncertain.blocksDependents());
        } finally { store.closeAsync().join(); }
        var reopened = SqliteCompanionStore.open(path, 3).join();
        try {
            assertEquals(1, reopened.unresolvedActions(id).join().size());
            assertEquals(ActionRecord.State.UNCERTAIN, reopened.findAction(takenIntent.actionId()).join().orElseThrow().state());
            BlockingQueue<Runnable> owner = new LinkedBlockingQueue<>();
            var replay = new ActionAdmission(takenIntent, "before", writer(reopened), owner::add);
            pumpUntil(replay, ActionAdmission.Phase.BLOCKED, owner);
            assertTrue(replay.take().isEmpty());
            assertThrows(CompletionException.class, () -> replay.close().join());
            assertEquals(1, reopened.unresolvedActions(id).join().size());
        } finally { reopened.closeAsync().join(); }
    }

    @Test void storedIntentFencesAndBeforeEvidenceCannotBeRewrittenByATransition() {
        var store = SqliteCompanionStore.open(directory.resolve("actions.sqlite"), 3).join();
        try {
            ActionRecord intent = intent(create(store));
            store.prepareAction(intent).join();
            var next = intent.dispatched("native before");
            for (ActionRecord forged : new ActionRecord[]{edit(next, 1, 0, next.preconditions(), next.beforeEvidence()),
                    edit(next, 0, 1, next.preconditions(), next.beforeEvidence()), edit(next, 0, 0, "changed target", next.beforeEvidence())}) {
                assertThrows(CompletionException.class, () -> store.transitionAction(forged, 0).join());
                assertEquals(intent, store.findAction(intent.actionId()).join().orElseThrow());
            }
            store.transitionAction(next, 0).join();
            var verified = next.verified("after", null);
            assertThrows(CompletionException.class, () -> store.transitionAction(edit(verified, 0, 0, verified.preconditions(), "rewritten before"), 1).join());
            assertEquals(next, store.findAction(intent.actionId()).join().orElseThrow());
            assertEquals(verified, store.transitionAction(verified, 1).join());
            assertEquals(verified, store.findAction(intent.actionId()).join().orElseThrow());
        } finally { store.closeAsync().join(); }
    }

    @Test void duplicateAdmissionObjectsCompeteForOneDurableDispatch() throws Exception {
        var store = SqliteCompanionStore.open(directory.resolve("actions.sqlite"), 3).join();
        try {
            ActionRecord intent = intent(create(store));
            BlockingQueue<Runnable> owner = new LinkedBlockingQueue<>();
            var first = new ActionAdmission(intent, "same before", writer(store), owner::add);
            var second = new ActionAdmission(intent, "same before", writer(store), owner::add);
            for (int i = 0; i < 8 && (pending(first) || pending(second)); i++) {
                Runnable callback = owner.poll(5, TimeUnit.SECONDS);
                assertNotNull(callback);
                callback.run();
            }
            int issued = (first.take().isPresent() ? 1 : 0) + (second.take().isPresent() ? 1 : 0);
            assertEquals(1, issued, "CAS dispatch acknowledgement admits exactly one object");
            var winner = first.phase() == ActionAdmission.Phase.ISSUED ? first : second;
            var loser = winner == first ? second : first;
            assertEquals(ActionAdmission.Phase.BLOCKED, loser.phase());
            assertEquals(ActionRecord.State.UNCERTAIN, winner.close().join().state());
            assertThrows(CompletionException.class, () -> loser.close().join());
            assertEquals(1, store.unresolvedActions(intent.companionId()).join().size());
        } finally { store.closeAsync().join(); }
    }

    private static boolean pending(ActionAdmission action) {
        return action.phase() == ActionAdmission.Phase.PREPARING || action.phase() == ActionAdmission.Phase.DISPATCHING;
    }

    private static ActionRecord edit(ActionRecord n, int intent, int task, String preconditions, String before) {
        return new ActionRecord(n.actionId(), n.taskId(), n.companionId(), n.epoch(), n.intentRevision() + intent, n.taskRevision() + task,
                n.kind(), preconditions, n.state(), before, n.afterEvidence(), n.outcome(), n.detail(), n.revision());
    }
    private static UUID create(SqliteCompanionStore store) {
        return store.create(new CreationRequest(UUID.randomUUID(), UUID.randomUUID(), "Ledger fixture", UUID.randomUUID(), "minecraft:overworld")).join().companion().id();
    }
    private static ActionRecord intent(UUID id) {
        return ActionRecord.prepare(UUID.randomUUID(), new ActionLease(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2, 3),
                "transfer", "owned source and empty destination observed");
    }
    private static ActionAdmission.Writer writer(SqliteCompanionStore store) {
        return new ActionAdmission.Writer() {
            public CompletableFuture<ActionRecord> prepare(ActionRecord record) { return store.prepareAction(record); }
            public CompletableFuture<ActionRecord> transition(ActionRecord next, long revision) { return store.transitionAction(next, revision); }
        };
    }
    private static void pumpUntil(ActionAdmission action, ActionAdmission.Phase expected, BlockingQueue<Runnable> owner) throws Exception {
        for (int i = 0; i < 5 && action.phase() != expected; i++) {
            Runnable callback = owner.poll(5, TimeUnit.SECONDS);
            assertNotNull(callback, "Expected durable owner acknowledgement");
            callback.run();
        }
        assertEquals(expected, action.phase());
    }
}
