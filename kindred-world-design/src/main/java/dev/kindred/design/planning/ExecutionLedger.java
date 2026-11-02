package dev.kindred.design.planning;

import dev.kindred.design.model.DesignModel;
import java.time.Instant;
import java.util.*;

/** Pure recovery ledger. It fences duplicate admission and refuses to replay uncertain mutations. */
public final class ExecutionLedger {
    public enum State { ADMITTED, ATTEMPTED, VERIFIED, FAILED_NO_MUTATION, UNCERTAIN, RECONCILED }
    public record Entry(String actionId, String designHash, String observationId, long observationRevision,
                        State state, Instant updatedAt, String receipt) {
        public Entry {
            actionId = DesignModel.id(actionId); Objects.requireNonNull(designHash); observationId = DesignModel.id(observationId);
            if (observationRevision < 0) throw new IllegalArgumentException("Negative observation revision");
            Objects.requireNonNull(state); Objects.requireNonNull(updatedAt); receipt = receipt == null ? "" : receipt;
            if (receipt.length() > 512) throw new IllegalArgumentException("Receipt exceeds bound");
        }
    }
    private final Map<String, Entry> entries = new HashMap<>();

    public synchronized Entry admit(String actionId, String designHash, String observationId, long observationRevision, Instant now) {
        Entry current = entries.get(actionId);
        if (current != null && current.state() != State.FAILED_NO_MUTATION && current.state() != State.RECONCILED)
            throw new IllegalStateException("Action has unresolved prior outcome: " + current.state());
        Entry admitted = new Entry(actionId, designHash, observationId, observationRevision, State.ADMITTED, now, "");
        entries.put(actionId, admitted); return admitted;
    }
    public synchronized Entry attempted(String actionId, Instant now) { return transition(actionId, State.ADMITTED, State.ATTEMPTED, now, ""); }
    public synchronized Entry outcome(String actionId, boolean nativeReceiptConfirmsMutation, boolean nativeReceiptConfirmsNoMutation,
                                      String receipt, Instant now) {
        if (nativeReceiptConfirmsMutation && nativeReceiptConfirmsNoMutation) throw new IllegalArgumentException("Contradictory receipt");
        State next = nativeReceiptConfirmsMutation ? State.VERIFIED : nativeReceiptConfirmsNoMutation ? State.FAILED_NO_MUTATION : State.UNCERTAIN;
        return transition(actionId, State.ATTEMPTED, next, now, receipt);
    }
    public synchronized Entry reconcile(String actionId, boolean safelySettled, String receipt, Instant now) {
        Entry current = require(actionId); if (current.state() != State.UNCERTAIN) throw new IllegalStateException("Only uncertain outcomes reconcile");
        if (!safelySettled) throw new IllegalStateException("Ambiguous action remains blocked");
        Entry next = new Entry(current.actionId(), current.designHash(), current.observationId(), current.observationRevision(), State.RECONCILED, now, receipt);
        entries.put(actionId, next); return next;
    }
    public synchronized Optional<Entry> find(String actionId) { return Optional.ofNullable(entries.get(actionId)); }
    private Entry transition(String actionId, State expected, State next, Instant now, String receipt) {
        Entry current = require(actionId); if (current.state() != expected) throw new IllegalStateException("Expected " + expected + ", got " + current.state());
        Entry updated = new Entry(current.actionId(), current.designHash(), current.observationId(), current.observationRevision(), next, now, receipt);
        entries.put(actionId, updated); return updated;
    }
    private Entry require(String actionId) { Entry entry = entries.get(actionId); if (entry == null) throw new IllegalArgumentException("Unknown action"); return entry; }
}
