package dev.kindred.core;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * In plain terms: the safety wrapper around one world action, such as breaking a block or moving items.
 * Before the action happens it is written into the action ledger; after it happens the game checks the world
 * and writes down whether it worked, failed or is uncertain. Nothing is ever repeated blindly after a crash.
 *
 * Technical: Single-use durable intent boundary around the existing ActionRecord writer. No callback performs
 * world work. A taken admission still needs current arbiter, payment, claims and native checks.
 * Owner-thread only; close flushes immutable records without needing another game-thread callback.
 */
public final class ActionAdmission {
    public enum Phase { PREPARING, DISPATCHING, READY, ISSUED, SETTLING, VERIFIED, FAILED, UNCERTAIN, BLOCKED, CLOSED }
    public interface Writer {
        CompletableFuture<ActionRecord> prepare(ActionRecord intent);
        CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision);
    }
    private final Thread owner = Thread.currentThread();
    private final Writer writer;
    private final Consumer<Runnable> dispatch;
    private ActionRecord record;
    private Phase phase = Phase.PREPARING;
    private boolean issued, closed;
    private CompletableFuture<ActionRecord> tail, closeFuture;

    /**
     * Starts writing the action down: first the plan, then that it is about to happen, with what the world
     * looked like before.
     */
    public ActionAdmission(ActionRecord intent, String beforeEvidence, Writer writer, Consumer<Runnable> dispatch) {
        record = Objects.requireNonNull(intent);
        this.writer = Objects.requireNonNull(writer);
        this.dispatch = Objects.requireNonNull(dispatch);
        if (intent.state() != ActionRecord.State.PREPARED || intent.revision() != 0) {
            throw new IllegalArgumentException("A fresh prepared intent is required");
        }
        ActionRecord next = intent.dispatched(Objects.requireNonNull(beforeEvidence)); // Validate before any IO.
        persist(intent, true, saved -> {
            phase = Phase.DISPATCHING;
            persist(next, false, ignored -> phase = Phase.READY);
        });
    }

    public Phase phase() { requireOwner(); return phase; }
    public ActionRecord snapshot() { requireOwner(); return record; }

    /** Consume once, on the owner, immediately before the adapter's final authority checks/effect. */
    public Optional<ActionRecord> take() {
        requireOwner();
        if (closed || phase != Phase.READY) return Optional.empty();
        issued = true;
        phase = Phase.ISSUED;
        return Optional.of(record);
    }

    /** Records that the action worked, with the evidence seen in the world afterwards. */
    public CompletableFuture<ActionRecord> verify(String afterEvidence, String note) {
        requireIssued();
        return finish(record.verified(afterEvidence, note), Phase.VERIFIED);
    }

    /** Records that the action was tried but changed nothing, with evidence. */
    public CompletableFuture<ActionRecord> noEffect(String afterEvidence, String reason) {
        requireIssued();
        return finish(record.failed(Objects.requireNonNull(afterEvidence), reason), Phase.FAILED);
    }

    /**
     * Records that nobody can tell whether the action happened. The player is then held until someone checks.
     */
    public CompletableFuture<ActionRecord> uncertain(String reason) {
        requireIssued();
        return finish(record.uncertain(reason), Phase.UNCERTAIN);
    }

    /** Saves the final outcome and marks the wrapper finished. */
    private CompletableFuture<ActionRecord> finish(ActionRecord next, Phase terminal) {
        phase = Phase.SETTLING;
        persist(next, false, ignored -> phase = terminal);
        return tail;
    }

    /** Revokes immediately. Unissued work can be proved absent; issued work needs adapter evidence. */
    public CompletableFuture<ActionRecord> close() {
        requireOwner();
        if (closeFuture != null) return closeFuture;
        closed = true;
        phase = Phase.CLOSED;
        boolean wasIssued = issued;
        closeFuture = tail.thenCompose(saved -> {
            ActionRecord next = switch (saved.state()) {
                case PREPARED -> saved.cancelled("Closed before body dispatch admission");
                case DISPATCHED -> wasIssued ? saved.uncertain("Admission issued without verified outcome; reconcile")
                        : saved.failed("Single-use body admission was never issued", "Closed before world dispatch");
                default -> saved;
            };
            if (next == saved) return CompletableFuture.completedFuture(saved);
            return checked(writer.transition(next, saved.revision()), next);
        });
        return closeFuture;
    }

    /** Saves the next state of the record, then carries on once the database confirms. */
    private void persist(ActionRecord next, boolean prepare, Consumer<ActionRecord> acknowledged) {
        try {
            tail = checked(prepare ? writer.prepare(next) : writer.transition(next, record.revision()), next);
        } catch (RuntimeException failure) { tail = CompletableFuture.failedFuture(failure); }
        tail.whenComplete((saved, error) -> dispatch.accept(() -> {
            requireOwner();
            if (closed) return;
            if (error != null) { phase = Phase.BLOCKED; return; }
            record = saved;
            acknowledged.accept(saved);
        }));
    }

    /** Makes sure the database saved exactly what was asked. */
    private static CompletableFuture<ActionRecord> checked(CompletableFuture<ActionRecord> future, ActionRecord expected) {
        return Objects.requireNonNull(future).thenApply(saved -> {
            if (!expected.equals(saved)) throw new IllegalStateException("Mismatched durable action acknowledgement");
            return saved;
        });
    }
    private void requireIssued() {
        requireOwner();
        if (closed || phase != Phase.ISSUED) throw new IllegalStateException("A single issued admission is required");
    }
    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Action admission belongs to its owner thread");
    }
}
