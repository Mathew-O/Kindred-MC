package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the record kept of each item-moving action: it is written before anything happens,
 * it goes through its steps in order, and a fault at any point never counts as success.
 */
class ActionRecordTest {
    private final ActionLease lease = new ActionLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 3, 2, 5);
    private final ActionRecord prepared = ActionRecord.prepare(UUID.randomUUID(), lease, "break_block", "oak_log at 1,64,1 v=12");

    @Test
    void intentIsRecordedBeforeAnyEffectAndSuccessNeedsAdapterEvidence() {
        assertEquals(ActionRecord.State.PREPARED, prepared.state());
        assertEquals(lease.sessionEpoch(), prepared.epoch());
        assertTrue(prepared.blocksDependents());
        assertThrows(IllegalStateException.class, () -> prepared.verified("drop seen", null), "nothing was dispatched yet");
        ActionRecord dispatched = prepared.dispatched("inventory 3 logs");
        assertTrue(dispatched.blocksDependents());
        assertThrows(IllegalArgumentException.class, () -> dispatched.verified(null, "committed"), "a commit alone is not proof");
        ActionRecord verified = dispatched.verified("inventory 4 logs", "pickup attributed");
        assertEquals(ActionRecord.Outcome.SUCCESS, verified.outcome());
        assertFalse(verified.blocksDependents());
        assertEquals(2, verified.revision());
        assertThrows(IllegalStateException.class, () -> verified.uncertain("late"), "terminal records never reopen");
    }

    @Test
    void faultsAtEveryBoundaryBecomeUncertainOrCleanFailureNeverSuccess() {
        // Before dispatch: a clean failure or cancellation, no world effect possible.
        assertEquals(ActionRecord.State.FAILED, prepared.failed(null, "permission revoked").state());
        assertEquals(ActionRecord.State.CANCELLED, prepared.cancelled("stop").state());
        // Crash between prepare and the dispatched write: ambiguous.
        assertEquals(ActionRecord.State.UNCERTAIN, prepared.uncertain("startup reconciliation").state());
        ActionRecord dispatched = prepared.dispatched("before");
        // After dispatch, "it failed" needs evidence that nothing happened; otherwise it is UNCERTAIN.
        assertThrows(IllegalStateException.class, () -> dispatched.failed(null, "timeout"));
        assertThrows(IllegalStateException.class, () -> dispatched.cancelled("stop"));
        ActionRecord uncertain = dispatched.uncertain("no verification before crash");
        assertTrue(uncertain.blocksDependents());
        assertThrows(IllegalStateException.class, () -> uncertain.verified("x", "y"), "uncertain cannot be verified without reconciliation");
        assertThrows(IllegalArgumentException.class, () -> uncertain.reconciled(true, null, "guess"));
        assertEquals(ActionRecord.State.VERIFIED, uncertain.reconciled(true, "block gone, 4 logs", "effect observed").state());
        assertEquals(ActionRecord.State.FAILED, uncertain.reconciled(false, "block intact, 3 logs", "no effect").state());
    }

    @Test
    void transitionTableMatchesTheRecordMethodsAndInconsistentRowsAreRejected() {
        for (ActionRecord.State from : ActionRecord.State.values()) {
            for (ActionRecord.State to : ActionRecord.State.values()) {
                boolean valid = ActionRecord.validTransition(from, to);
                if (from.terminal()) assertFalse(valid, from + "->" + to);
                if (to == ActionRecord.State.VERIFIED) assertEquals(from == ActionRecord.State.DISPATCHED || from == ActionRecord.State.UNCERTAIN, valid);
                if (to == ActionRecord.State.CANCELLED) assertEquals(from == ActionRecord.State.PREPARED, valid);
                if (to == ActionRecord.State.PREPARED) assertFalse(valid);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new ActionRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                0, 0, "k", null, ActionRecord.State.VERIFIED, null, null, ActionRecord.Outcome.SUCCESS, null, 0), "verified needs evidence");
        assertThrows(IllegalArgumentException.class, () -> new ActionRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                0, 0, "k", null, ActionRecord.State.UNCERTAIN, null, null, ActionRecord.Outcome.SUCCESS, null, 0), "uncertain is never a success");
        assertThrows(IllegalArgumentException.class, () -> ActionRecord.prepare(UUID.randomUUID(), lease, " ", null));
    }
}
