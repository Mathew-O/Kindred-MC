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
 * In plain terms: the meter that runs while a player works. It books a few seconds of play time, waits for
 * the database to confirm, lets the player act, renews the slice while a long job runs, and settles up what
 * was really used when the job ends or is stopped.
 *
 * Technical: Owner-thread prepaid activity lifecycle. The injected writer is the existing ordered store;
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
    // A renewal written but not yet acknowledged: its reservation, when it was asked for, and when a stop cut it short.
    private boolean renewing;
    private UUID renewalId;
    private long renewalStarted, stoppedNanos;
    private CompletableFuture<Boolean> renewal;
    private CompletableFuture<BudgetState> tail;
    private CompletableFuture<BudgetState> closeFuture;

    /**
     * Starts a meter for one player's day, with how it saves (writer) and how it gets back onto the game
     * thread (dispatch).
     */
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

    /**
     * Books the first slice, no later than the deadline. Completes true once the database confirms and the
     * player may act.
     */
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

    /**
     * Renews the active slice for work that runs on (F-11c): one durable write settles the active reservation up to
     * now and reserves the next. The active permit governs until that write is acknowledged on the owner thread; the
     * next permit counts from this call, so the write's latency is charged and shortens the new slice, and neither is
     * ever extended. Completes false without a write when there is nothing to renew (not active, already renewing,
     * closed, a lapsed permit or a backwards sample) or no allowance or eligible time is left; the active permit then
     * lapses as usual. Never called by the session itself.
     */
    public CompletableFuture<Boolean> renew(Instant hardDeadline) {
        requireOwner();
        Objects.requireNonNull(hardDeadline);
        if (closed || phase != Phase.ACTIVE || permit == null || renewing) return CompletableFuture.completedFuture(false);
        long started = nanos.getAsLong();
        Optional<Duration> observed = permit.observedUntil(started);
        if (observed.isEmpty() || permit.remainingAt(started).isZero()) return CompletableFuture.completedFuture(false);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        BudgetState current = budget;
        BudgetState next = current.renew(permit.reservationId(), observed.orElseThrow(), id, BudgetState.MAX_SLICE, cap, now, hardDeadline);
        if (next.outstanding().isEmpty()) {
            blocker = "No allowance or eligible accounting interval";
            return CompletableFuture.completedFuture(false);
        }
        long epoch = generation;
        renewing = true;
        renewalId = id;
        renewalStarted = started;
        renewal = new CompletableFuture<>();
        CompletableFuture<Boolean> result = renewal;
        persist(next, current.sequence(), saved -> {
            renewing = false;
            if (epoch != generation) { // Stopped or lapsed meanwhile: the new slice ran only until then.
                settleRenewal(saved, id, started, stoppedNanos);
                result.complete(false);
                return;
            }
            Optional<ActivityPermit> admitted = ActivityPermit.acknowledged(saved, id, clock.instant(), started);
            if (admitted.isEmpty()) {
                blocker = "Renewal acknowledged after its slice";
                permit = null;
                settleRenewal(saved, id, started, nanos.getAsLong());
                result.complete(false);
            } else {
                permit = admitted.orElseThrow();
                blocker = "";
                result.complete(true);
            }
        });
        return result;
    }

    /** Whether a renewal is written and waiting for its acknowledgement. */
    public boolean renewing() { requireOwner(); return renewing; }

    /** Time left on the active permit; zero when there is none. */
    public Duration remaining() {
        requireOwner();
        return phase == Phase.ACTIVE && permit != null && !closed ? permit.remainingAt(nanos.getAsLong()) : Duration.ZERO;
    }

    /**
     * Whether the player may act right now. When the slice has run out it stops the meter and answers false.
     */
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
        if (renewal != null && !renewal.isDone()) renewal.complete(false);
        if (phase == Phase.ACTIVE) {
            ActivityPermit old = permit;
            permit = null;
            phase = Phase.SETTLING;
            // A renewal's write is in flight: its acknowledgement settles the new slice up to now, in order.
            if (renewing) { stoppedNanos = nanos.getAsLong(); return; }
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
        // A renewal in flight: the new slice ran from its request until now, or until an earlier stop.
        boolean renewalPending = renewing;
        UUID pendingId = renewalId;
        long pendingStarted = renewalStarted, pendingUntil = active != null ? cutoff : stoppedNanos;
        permit = null;
        phase = Phase.CLOSED;
        if (request != null && !request.isDone()) request.complete(false);
        if (renewal != null && !renewal.isDone()) renewal.complete(false);
        closeFuture = tail.thenCompose(saved -> {
            if (saved.outstanding().isEmpty()) return CompletableFuture.completedFuture(saved);
            BudgetState settled = renewalPending && saved.outstanding().orElseThrow().id().equals(pendingId)
                    ? renewalSettlement(saved, pendingId, pendingStarted, pendingUntil)
                    : active == null ? saved.settle(saved.outstanding().orElseThrow().id(), Duration.ZERO)
                    : active.settle(saved, cutoff);
            return checked(writer.save(settled, saved.sequence()), settled);
        });
        return closeFuture;
    }

    /** Settles a renewed reservation that will not become the active permit, for the time it ran. */
    private void settleRenewal(BudgetState saved, UUID id, long started, long until) {
        phase = Phase.SETTLING;
        persist(renewalSettlement(saved, id, started, until), saved.sequence(), ignored -> phase = Phase.IDLE);
    }

    /** From the renewal's request until {@code until}, rounded up; an ambiguous sample keeps the whole prepayment. */
    private static BudgetState renewalSettlement(BudgetState saved, UUID id, long started, long until) {
        long elapsed = until - started;
        if (elapsed < 0) return saved.recoverAfterCrash();
        long millis = Math.min(saved.outstanding().orElseThrow().reservedMillis(), (elapsed + 999_999) / 1_000_000);
        return saved.settle(id, Duration.ofMillis(millis));
    }

    /** Gives back a booked slice that was never used. */
    private void settleUnused() {
        phase = Phase.SETTLING;
        BudgetState settled = budget.settle(budget.outstanding().orElseThrow().id(), Duration.ZERO);
        persist(settled, budget.sequence(), ignored -> phase = Phase.IDLE);
    }

    /**
     * Saves the next tally in order, then runs the follow-up on the game thread. A failed save blocks the
     * meter rather than guessing.
     */
    private void persist(BudgetState next, long expected, Consumer<BudgetState> acknowledged) {
        try { tail = checked(writer.save(next, expected), next); }
        catch (RuntimeException failure) { tail = CompletableFuture.failedFuture(failure); }
        tail.whenComplete((saved, error) -> dispatch.accept(() -> {
            requireOwner();
            if (closed) return;
            if (error != null) {
                permit = null;
                phase = Phase.BLOCKED;
                renewing = false;
                blocker = "Activity write outcome uncertain; reload/reconciliation required";
                if (request != null && !request.isDone()) request.completeExceptionally(error);
                if (renewal != null && !renewal.isDone()) renewal.completeExceptionally(error);
                return;
            }
            budget = saved;
            acknowledged.accept(saved);
        }));
    }

    /** Makes sure the database saved exactly what was asked. */
    private static CompletableFuture<BudgetState> checked(CompletableFuture<BudgetState> future, BudgetState expected) {
        return Objects.requireNonNull(future).thenApply(saved -> {
            if (!expected.equals(saved)) throw new IllegalStateException("Mismatched durable budget acknowledgement");
            return saved;
        });
    }

    /** Guards against use from the wrong thread. */
    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Activity session belongs to its owner thread");
    }
}
