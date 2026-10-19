package dev.kindred.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Owner-thread prepaid activity lifecycle. The injected writer is the existing ordered store;
 * callbacks receive immutable budget values. This class owns no body, admission or work queue.
 * A caller must additionally recheck its action authority before using an acknowledged permit.
 */
public final class PaidActivitySession {
    public enum Phase { IDLE, RESERVING, ACTIVE, SETTLING, BLOCKED, CLOSED }
    @FunctionalInterface public interface Writer {
        CompletableFuture<BudgetState> save(BudgetState next, long expectedSequence);
    }

    private final Thread owner = Thread.currentThread();
    private final Duration cap;
    private final Writer writer;
    private final Consumer<Runnable> dispatch;
    private final Clock clock;
    private final LongSupplier nanos;
    private BudgetState budget;
    private ActivityPermit permit;
    private Phase phase = Phase.IDLE;
    private long generation;
    private boolean closed;
    private String blocker = "";
    private CompletableFuture<Boolean> request;
    private CompletableFuture<BudgetState> tail;
    private CompletableFuture<BudgetState> closeFuture;

    public PaidActivitySession(BudgetState budget, Duration cap, Writer writer, Consumer<Runnable> dispatch,
                               Clock clock, LongSupplier nanos) {
        this.budget = Objects.requireNonNull(budget);
        this.cap = Objects.requireNonNull(cap);
        this.writer = Objects.requireNonNull(writer);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.clock = Objects.requireNonNull(clock);
        this.nanos = Objects.requireNonNull(nanos);
        ActivitySchedule.requirePositiveMillis(cap, "cap");
        if (budget.outstanding().isPresent()) throw new IllegalArgumentException("Recover outstanding activity before starting a session");
        tail = CompletableFuture.completedFuture(budget);
    }

    public Phase phase() { requireOwner(); return phase; }
    public BudgetState snapshot() { requireOwner(); return budget; }
    public String blocker() { requireOwner(); return blocker; }

    public CompletableFuture<Boolean> request(Instant hardDeadline) {
        requireOwner();
        Objects.requireNonNull(hardDeadline);
        if (closed || phase != Phase.IDLE) return CompletableFuture.completedFuture(false);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        BudgetState next = budget.reserve(id, BudgetState.MAX_SLICE, cap, now, hardDeadline);
        if (next.outstanding().isEmpty()) {
            blocker = "No allowance or eligible accounting interval";
            return CompletableFuture.completedFuture(false);
        }
        long epoch = ++generation;
        request = new CompletableFuture<>();
        CompletableFuture<Boolean> result = request;
        phase = Phase.RESERVING;
        persist(next, budget.sequence(), saved -> {
            if (epoch != generation) { settleUnused(); result.complete(false); return; }
            Optional<ActivityPermit> admitted = ActivityPermit.acknowledged(saved, id, clock.instant(), nanos.getAsLong());
            if (admitted.isEmpty()) {
                blocker = "Reservation expired before acknowledgement";
                settleUnused();
                result.complete(false);
            } else {
                permit = admitted.orElseThrow();
                phase = Phase.ACTIVE;
                blocker = "";
                result.complete(true);
            }
        });
        return result;
    }

    public boolean paid() {
        requireOwner();
        if (phase != Phase.ACTIVE || permit == null || closed) return false;
        if (permit.allows(budget.companionId(), clock.instant(), nanos.getAsLong())) return true;
        blocker = "Prepaid activity deadline reached";
        stop();
        return false;
    }

    /** Immediate revocation; bookkeeping may finish later and never starts work. */
    public void stop() {
        requireOwner();
        if (closed) return;
        ++generation;
        if (request != null && !request.isDone()) request.complete(false);
        if (phase == Phase.ACTIVE) {
            ActivityPermit old = permit;
            permit = null;
            phase = Phase.SETTLING;
            persist(old.settle(budget, nanos.getAsLong()), budget.sequence(), ignored -> phase = Phase.IDLE);
        }
    }

    /**
     * Flush without waiting for another owner-thread tick. Only immutable budget data is used by
     * the IO continuation; normal callbacks are suppressed after closed is set on the owner.
     */
    public CompletableFuture<BudgetState> close() {
        requireOwner();
        if (closeFuture != null) return closeFuture;
        closed = true;
        ++generation;
        ActivityPermit active = permit;
        long cutoff = nanos.getAsLong();
        permit = null;
        phase = Phase.CLOSED;
        if (request != null && !request.isDone()) request.complete(false);
        closeFuture = tail.thenCompose(saved -> {
            if (saved.outstanding().isEmpty()) return CompletableFuture.completedFuture(saved);
            BudgetState settled = active == null ? saved.settle(saved.outstanding().orElseThrow().id(), Duration.ZERO)
                    : active.settle(saved, cutoff);
            return checked(writer.save(settled, saved.sequence()), settled);
        });
        return closeFuture;
    }

    private void settleUnused() {
        phase = Phase.SETTLING;
        BudgetState settled = budget.settle(budget.outstanding().orElseThrow().id(), Duration.ZERO);
        persist(settled, budget.sequence(), ignored -> phase = Phase.IDLE);
    }

    private void persist(BudgetState next, long expected, Consumer<BudgetState> acknowledged) {
        try { tail = checked(writer.save(next, expected), next); }
        catch (RuntimeException failure) { tail = CompletableFuture.failedFuture(failure); }
        tail.whenComplete((saved, error) -> dispatch.accept(() -> {
            requireOwner();
            if (closed) return;
            if (error != null) {
                permit = null;
                phase = Phase.BLOCKED;
                blocker = "Activity write outcome uncertain; reload/reconciliation required";
                if (request != null && !request.isDone()) request.completeExceptionally(error);
                return;
            }
            budget = saved;
            acknowledged.accept(saved);
        }));
    }

    private static CompletableFuture<BudgetState> checked(CompletableFuture<BudgetState> future, BudgetState expected) {
        return Objects.requireNonNull(future).thenApply(saved -> {
            if (!expected.equals(saved)) throw new IllegalStateException("Mismatched durable budget acknowledgement");
            return saved;
        });
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Activity session belongs to its owner thread");
    }
}
