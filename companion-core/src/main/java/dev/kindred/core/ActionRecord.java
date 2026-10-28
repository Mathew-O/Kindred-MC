package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: one entry in the action ledger: what the player meant to do, what the world looked like
 * before, and what happened. An entry moves from prepared, to dispatched, to verified, failed or cancelled;
 * one whose outcome is unknown is marked uncertain and blocks anything that depends on it until a person
 * checks.
 *
 * Technical: One nontrivial world action's durable intent and outcome. Intent is recorded before the side
 * effect; the outcome is recorded only from adapter evidence. An unknown outcome is UNCERTAIN and blocks
 * dependents until an explicit reconciliation supplies evidence; nothing here can turn "unknown" into
 * "success".
 */
public record ActionRecord(UUID actionId, UUID taskId, UUID companionId, UUID epoch, long intentRevision, long taskRevision,
                           String kind, String preconditions, State state, String beforeEvidence, String afterEvidence,
                           Outcome outcome, String detail, long revision) {
    public static final int MAX_TEXT = 4_096;

    public enum State {
        PREPARED, DISPATCHED, VERIFIED, FAILED, CANCELLED, UNCERTAIN;

        public boolean terminal() { return this == VERIFIED || this == FAILED || this == CANCELLED; }
    }

    public enum Outcome { NONE, SUCCESS, FAILURE, UNKNOWN }

    public ActionRecord {
        Objects.requireNonNull(actionId);
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(epoch);
        Objects.requireNonNull(state);
        Objects.requireNonNull(outcome);
        kind = text(kind, "kind", false);
        preconditions = text(preconditions, "preconditions", true);
        beforeEvidence = text(beforeEvidence, "beforeEvidence", true);
        afterEvidence = text(afterEvidence, "afterEvidence", true);
        detail = text(detail, "detail", true);
        if (intentRevision < 0 || taskRevision < 0 || revision < 0) throw new IllegalArgumentException("Fences cannot be negative");
        boolean consistent = switch (state) {
            case PREPARED, DISPATCHED -> outcome == Outcome.NONE;
            case VERIFIED -> outcome == Outcome.SUCCESS && afterEvidence != null;
            case FAILED -> outcome == Outcome.FAILURE;
            case CANCELLED -> outcome == Outcome.NONE || outcome == Outcome.FAILURE;
            case UNCERTAIN -> outcome == Outcome.UNKNOWN;
        };
        if (!consistent) throw new IllegalArgumentException("Outcome " + outcome + " is inconsistent with state " + state);
    }

    /** A new entry for an action that is planned but has not happened yet. */
    public static ActionRecord prepare(UUID actionId, ActionLease lease, String kind, String preconditions) {
        Objects.requireNonNull(lease);
        return new ActionRecord(actionId, lease.taskId(), lease.companionId(), lease.sessionEpoch(), lease.intentRevision(),
                lease.taskRevision(), kind, preconditions, State.PREPARED, null, null, Outcome.NONE, null, 0);
    }

    /** The side effect has been handed to the world; nothing about its result is known yet. */
    public ActionRecord dispatched(String evidenceBefore) {
        require(State.PREPARED, "dispatch");
        return next(State.DISPATCHED, evidenceBefore, afterEvidence, Outcome.NONE, detail);
    }

    /** Only an adapter postcondition verifies success; a database commit, swing or model statement never does. */
    public ActionRecord verified(String evidenceAfter, String note) {
        require(State.DISPATCHED, "verify");
        if (evidenceAfter == null) throw new IllegalArgumentException("Verification requires adapter evidence");
        return next(State.VERIFIED, beforeEvidence, evidenceAfter, Outcome.SUCCESS, note);
    }

    /** Failure before dispatch needs no evidence; after dispatch it needs evidence that no effect occurred. */
    public ActionRecord failed(String evidenceAfter, String reason) {
        if (state == State.PREPARED) return next(State.FAILED, beforeEvidence, evidenceAfter, Outcome.FAILURE, reason);
        if (state == State.DISPATCHED) {
            if (evidenceAfter == null) throw new IllegalStateException("A dispatched action without evidence is UNCERTAIN, not FAILED");
            return next(State.FAILED, beforeEvidence, evidenceAfter, Outcome.FAILURE, reason);
        }
        throw new IllegalStateException("Cannot fail an action in state " + state);
    }

    /** Cancellation is only clean before dispatch; afterwards the world may already have changed. */
    public ActionRecord cancelled(String reason) {
        require(State.PREPARED, "cancel");
        return next(State.CANCELLED, beforeEvidence, afterEvidence, Outcome.NONE, reason);
    }

    /** Crash, timeout or conflicting evidence between intent and verification. Blocks dependents. */
    public ActionRecord uncertain(String reason) {
        if (state != State.PREPARED && state != State.DISPATCHED) throw new IllegalStateException("Cannot mark " + state + " uncertain");
        return next(State.UNCERTAIN, beforeEvidence, afterEvidence, Outcome.UNKNOWN, reason);
    }

    /** Explicit reconciliation with fresh world evidence resolves an uncertain action one way or the other. */
    public ActionRecord reconciled(boolean effectObserved, String evidenceAfter, String note) {
        require(State.UNCERTAIN, "reconcile");
        if (evidenceAfter == null) throw new IllegalArgumentException("Reconciliation requires world evidence");
        return effectObserved ? next(State.VERIFIED, beforeEvidence, evidenceAfter, Outcome.SUCCESS, note)
                : next(State.FAILED, beforeEvidence, evidenceAfter, Outcome.FAILURE, note);
    }

    /** True while a dependent action must not start. */
    public boolean blocksDependents() { return state == State.UNCERTAIN || state == State.DISPATCHED || state == State.PREPARED; }

    /** Valid durable transitions; the store enforces this against the stored row. */
    public static boolean validTransition(State from, State to) {
        return switch (from) {
            case PREPARED -> to == State.DISPATCHED || to == State.FAILED || to == State.CANCELLED || to == State.UNCERTAIN;
            case DISPATCHED -> to == State.VERIFIED || to == State.FAILED || to == State.UNCERTAIN;
            case UNCERTAIN -> to == State.VERIFIED || to == State.FAILED;
            case VERIFIED, FAILED, CANCELLED -> false;
        };
    }

    /** The same entry moved on to its next state, with its revision counter increased. */
    private ActionRecord next(State nextState, String before, String after, Outcome nextOutcome, String note) {
        return new ActionRecord(actionId, taskId, companionId, epoch, intentRevision, taskRevision, kind, preconditions,
                nextState, before, after, nextOutcome, note, Math.incrementExact(revision));
    }

    private void require(State expected, String verb) {
        if (state != expected) throw new IllegalStateException("Cannot " + verb + " an action in state " + state);
    }

    private static String text(String value, String field, boolean nullable) {
        if (value == null) {
            if (nullable) return null;
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.isBlank() || value.length() > MAX_TEXT) throw new IllegalArgumentException(field + " must be 1.." + MAX_TEXT + " characters");
        return value;
    }
}
