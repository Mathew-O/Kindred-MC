package dev.kindred.core;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the write-it-down-first rule for actions that move items: nothing happens until the
 * record is safely saved, and an unclear outcome stops for checking instead of being repeated.
 */
class ActionAdmissionTest {
    @Test void bothDurableAcknowledgementsAndOwnerDispatchPrecedeSingleUse() {
        Harness h = new Harness();
        assertTrue(h.action.take().isEmpty());
        h.ack();
        assertTrue(h.action.take().isEmpty());
        h.pump();
        assertEquals(ActionAdmission.Phase.DISPATCHING, h.action.phase());
        assertEquals(0, h.calls.get(1).expected);
        assertTrue(h.action.take().isEmpty());
        h.ack();
        assertTrue(h.action.take().isEmpty());
        h.pump();
        assertEquals(ActionRecord.State.DISPATCHED, h.action.take().orElseThrow().state());
        assertTrue(h.action.take().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> h.action.verify(null, "unproven"));
    }

    @Test void verificationWaitsForMatchingDurableOutcome() {
        Harness h = new Harness(); h.ready(); h.action.take();
        var outcome = h.action.verify("owned inventory 1 log; attributed pickup", "verified natively");
        assertEquals(ActionAdmission.Phase.SETTLING, h.action.phase());
        assertFalse(outcome.isDone());
        assertTrue(h.action.take().isEmpty());
        h.ack(); h.pump();
        assertEquals(ActionAdmission.Phase.VERIFIED, h.action.phase());
        assertEquals(ActionRecord.Outcome.SUCCESS, outcome.join().outcome());
        assertThrows(IllegalStateException.class, () -> h.action.verify("duplicate", null));
        assertEquals(outcome.join(), h.action.close().join());
    }

    @Test void noEffectRequiresEvidenceAndUnknownNeverBecomesFailure() {
        Harness noEffect = new Harness(); noEffect.ready(); noEffect.action.take();
        assertThrows(NullPointerException.class, () -> noEffect.action.noEffect(null, "guess"));
        var failed = noEffect.action.noEffect("block intact, unchanged tool and inventory", "cancelled");
        noEffect.ack(); noEffect.pump();
        assertEquals(ActionRecord.State.FAILED, failed.join().state());
        Harness unknown = new Harness(); unknown.ready(); unknown.action.take();
        var uncertain = unknown.action.uncertain("postcondition conflicts");
        unknown.ack(); unknown.pump();
        assertEquals(ActionAdmission.Phase.UNCERTAIN, unknown.action.phase());
        assertTrue(uncertain.join().blocksDependents());
        assertTrue(unknown.action.take().isEmpty());
    }

    @Test void closeDuringPreparationCancelsWithoutAnotherOwnerCallback() {
        Harness h = new Harness();
        var closed = h.action.close();
        assertSame(closed, h.action.close());
        assertTrue(h.action.take().isEmpty());
        h.ack(); // IO continuation writes cancellation while the owner queue stays untouched.
        assertEquals(ActionRecord.State.CANCELLED, h.pending.getFirst().next.state());
        h.ack();
        assertEquals(ActionRecord.State.CANCELLED, closed.join().state());
        h.pump();
        assertEquals(ActionAdmission.Phase.CLOSED, h.action.phase());
        assertEquals(2, h.calls.size());
    }

    @Test void closeWhileDispatchAckIsPendingProvesUnissuedWorkWithoutOwnerTicks() {
        Harness h = new Harness(); h.ack(); h.pump();
        var closed = h.action.close();
        h.ack();
        assertEquals(ActionRecord.State.FAILED, h.pending.getFirst().next.state());
        assertNotNull(h.pending.getFirst().next.afterEvidence());
        h.ack();
        assertEquals(ActionRecord.State.FAILED, closed.join().state());
        h.pump();
        assertTrue(h.action.take().isEmpty());
    }

    @Test void readyButUntakenClosesCleanlyAndTakenClosesUncertain() {
        Harness ready = new Harness(); ready.ready();
        var absent = ready.action.close(); ready.ack();
        assertEquals(ActionRecord.State.FAILED, absent.join().state());
        Harness taken = new Harness(); taken.ready(); taken.action.take();
        var unknown = taken.action.close(); taken.ack();
        assertEquals(ActionRecord.State.UNCERTAIN, unknown.join().state());
        assertTrue(unknown.join().blocksDependents());
        assertTrue(taken.action.take().isEmpty());
    }

    @Test void closeWaitsForPendingVerificationAndNeverDowngradesItsEvidence() {
        Harness h = new Harness(); h.ready(); h.action.take();
        h.action.verify("exact native postcondition", null);
        var closed = h.action.close();
        h.ack();
        assertEquals(ActionRecord.State.VERIFIED, closed.join().state());
        assertEquals(3, h.calls.size());
        h.pump();
        assertEquals(ActionAdmission.Phase.CLOSED, h.action.phase());
    }

    @Test void mismatchedAndUnknownAcknowledgementsBlockWithoutRetryOrAdmission() {
        for (int boundary = 0; boundary < 3; boundary++) {
            Harness h = new Harness();
            if (boundary > 0) { h.ack(); h.pump(); }
            if (boundary > 1) { h.ack(); h.pump(); h.action.take(); h.action.verify("postcondition", null); }
            var call = h.pending.removeFirst();
            call.future.complete(h.intent.uncertain("wrong acknowledgement"));
            h.pump();
            assertEquals(ActionAdmission.Phase.BLOCKED, h.action.phase());
            assertTrue(h.action.take().isEmpty());
            assertEquals(boundary + 1, h.calls.size());
            assertThrows(CompletionException.class, () -> h.action.close().join());
        }
    }

    @Test void failedWritersNeverRetryAtAnyBoundary() {
        for (int boundary = 0; boundary < 3; boundary++) {
            Harness h = new Harness();
            if (boundary > 0) { h.ack(); h.pump(); }
            if (boundary > 1) { h.ack(); h.pump(); h.action.take(); h.action.verify("after", null); }
            h.pending.removeFirst().future.completeExceptionally(new IllegalStateException("unknown IO outcome"));
            h.pump();
            assertEquals(ActionAdmission.Phase.BLOCKED, h.action.phase());
            assertTrue(h.action.take().isEmpty());
            assertEquals(boundary + 1, h.calls.size());
        }
    }

    @Test void invalidIntentAndOffThreadCallsCannotIssueWork() {
        Harness h = new Harness();
        assertThrows(IllegalStateException.class, () -> h.action.verify("too early", null));
        assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> h.action.take()).join());
        assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> h.action.close()).join());
        assertThrows(IllegalArgumentException.class, () -> new ActionAdmission(h.intent.dispatched("before"), "before", h, h.owner::add));
        assertThrows(NullPointerException.class, () -> new ActionAdmission(h.intent, null, h, h.owner::add));
        assertEquals(1, h.calls.size());
    }

    private record Call(ActionRecord next, long expected, CompletableFuture<ActionRecord> future) { }
    private static final class Harness implements ActionAdmission.Writer {
        final ActionRecord intent = ActionRecord.prepare(UUID.randomUUID(), new ActionLease(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, 2, 3), "mine", "observed oak log at target");
        final ArrayDeque<Runnable> owner = new ArrayDeque<>();
        final ArrayDeque<Call> pending = new ArrayDeque<>();
        final List<Call> calls = new ArrayList<>();
        final ActionAdmission action = new ActionAdmission(intent, "block intact; tool durability 7", this, owner::add);
        @Override public CompletableFuture<ActionRecord> prepare(ActionRecord next) { return save(next, -1); }
        @Override public CompletableFuture<ActionRecord> transition(ActionRecord next, long expected) { return save(next, expected); }
        private CompletableFuture<ActionRecord> save(ActionRecord next, long expected) {
            var call = new Call(next, expected, new CompletableFuture<>()); calls.add(call); pending.add(call); return call.future;
        }
        void ack() { var call = pending.removeFirst(); call.future.complete(call.next); }
        void pump() { while (!owner.isEmpty()) owner.removeFirst().run(); }
        void ready() { ack(); pump(); ack(); pump(); assertEquals(ActionAdmission.Phase.READY, action.phase()); }
    }
}
