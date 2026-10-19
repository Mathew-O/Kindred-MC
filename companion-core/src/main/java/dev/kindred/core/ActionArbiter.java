package dev.kindred.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

import static dev.kindred.core.ControlRejectedException.Reason;

/**
 * Single-thread body authority. Admission, prepaid activity and world permissions are
 * prerequisites enforced by the caller; an action lease alone is not world permission.
 * No work queue is retained here, so stop leaves no queued action able to restart.
 */
public final class ActionArbiter {
    private final Thread ownerThread = Thread.currentThread();
    private final UUID sessionEpoch;
    private final BodyAdapter adapter;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final long cancellationTimeoutNanos;
    private Companion companion;
    private long bodyGeneration;
    private ActionLease active;
    private long activeSince;
    private long activeTimeoutNanos;
    private ActionLease cancelling;
    private long cancellationSince;

    public ActionArbiter(Companion companion, BodyAdapter adapter) {
        this(companion, UUID.randomUUID(), adapter, Clock.systemUTC(), System::nanoTime,
                Duration.ofMillis(500));
    }

    /**
     * Create after native startup reconciliation with a fresh epoch per runtime session.
     * Restored active states are dormant until explicitly readmitted. Stop is durable.
     */
    public ActionArbiter(Companion companion, UUID sessionEpoch, BodyAdapter adapter,
                         Clock clock, LongSupplier nanoTime, Duration cancellationTimeout) {
        this.companion = Objects.requireNonNull(companion, "companion");
        this.sessionEpoch = Objects.requireNonNull(sessionEpoch, "sessionEpoch");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.cancellationTimeoutNanos = positiveNanos(cancellationTimeout);
        if (companion.lifecycle() != LifecycleState.FAULTED
                && companion.lifecycle() != LifecycleState.REMOVED) {
            this.companion = companion.withLifecycle(companion.stopLatched()
                    ? LifecycleState.PAUSED : LifecycleState.SLEEPING);
        }
    }

    public Companion snapshot() {
        requireThread();
        return companion;
    }

    public UUID sessionEpoch() {
        return sessionEpoch;
    }

    /** Direct work requires explicit resume after stop. Authorization precedes all mutation. */
    public ActionLease acquire(Actor actor, UUID taskId, UUID actionId, Duration timeout) {
        requireThread();
        Authorization.requireControl(actor, companion);
        return begin(taskId, actionId, timeout, true);
    }

    /** For trusted scheduler/director code only, after all eligibility checks and reservations. */
    public ActionLease acquireAutonomous(UUID taskId, UUID actionId, Duration timeout) {
        requireThread();
        return begin(taskId, actionId, timeout, false);
    }

    public void stop(Actor actor) {
        requireThread();
        Authorization.requireControl(actor, companion);
        if (companion.lifecycle() == LifecycleState.REMOVED) {
            throw rejected(Reason.REMOVED, "This companion has been removed");
        }
        companion = companion.withRevisions(Math.incrementExact(companion.intentRevision()),
                companion.taskRevision()).withStopLatched(true);
        bodyGeneration = Math.incrementExact(bodyGeneration);
        if (companion.lifecycle() != LifecycleState.FAULTED) {
            companion = companion.withLifecycle(LifecycleState.PAUSED);
        }
        cancelActive();
    }

    /** Clears the stop latch, but neither grants admission nor bypasses a cancellation barrier. */
    public void resume(Actor actor) {
        requireThread();
        Authorization.requireControl(actor, companion);
        requireUsable();
        if (!companion.stopLatched()) return;
        companion = companion.withRevisions(Math.incrementExact(companion.intentRevision()),
                companion.taskRevision()).withStopLatched(false);
        if (active == null) companion = companion.withLifecycle(LifecycleState.WAITING);
    }

    /**
     * Release a successfully completed skill only after the caller verifies its postcondition.
     * The adapter must additionally confirm there is no operation still controlling the body.
     */
    public boolean complete(ActionLease lease) {
        requireThread();
        if (!isCurrent(lease)) return false;
        try {
            if (!adapter.isSettled(lease)) return false;
        } catch (RuntimeException failure) {
            companion = companion.withLifecycle(LifecycleState.FAULTED);
            bodyGeneration = Math.incrementExact(bodyGeneration);
            cancelActive();
            return false;
        }
        active = null;
        bodyGeneration = Math.incrementExact(bodyGeneration);
        companion = companion.withRevisions(companion.intentRevision(),
                Math.incrementExact(companion.taskRevision())).withLifecycle(LifecycleState.WAITING);
        return true;
    }

    /**
     * End the current lease without the stop latch: a pause that could not reach a checkpoint, a skill that
     * blocked, or a direct command replaced by another. Enters the same cancellation barrier as stop, so no
     * replacement starts until the body settles. Returns false for a stale lease.
     */
    public boolean cancel(ActionLease lease) {
        requireThread();
        if (active == null || !active.equals(lease)) return false;
        bodyGeneration = Math.incrementExact(bodyGeneration);
        companion = companion.withRevisions(companion.intentRevision(), Math.incrementExact(companion.taskRevision()));
        if (companion.lifecycle() != LifecycleState.FAULTED) companion = companion.withLifecycle(LifecycleState.WAITING);
        cancelActive();
        return true;
    }

    /** True while a cancelled or stopped action has not yet settled; nothing new may start meanwhile. */
    public boolean cancellationPending() {
        requireThread();
        settleCancellation();
        return cancelling != null;
    }

    /** Called by the server control loop before dispatching this tick's actions. */
    public void tick() {
        requireThread();
        if (active != null && elapsed(activeSince) >= activeTimeoutNanos) {
            bodyGeneration = Math.incrementExact(bodyGeneration);
            companion = companion.withLifecycle(LifecycleState.BLOCKED);
            cancelActive();
        }
        settleCancellation();
    }

    /** Read-only validity check; execution still must recheck world permissions and evidence. */
    public boolean isCurrent(ActionLease lease) {
        requireThread();
        return active != null && active.equals(lease)
                && !companion.stopLatched()
                && companion.lifecycle() == LifecycleState.ACTIVE
                && active.intentRevision() == companion.intentRevision()
                && active.taskRevision() == companion.taskRevision()
                && elapsed(activeSince) < activeTimeoutNanos;
    }

    public boolean executeIfCurrent(ActionLease lease, Runnable action) {
        requireThread();
        Objects.requireNonNull(action, "action");
        if (!isCurrent(lease)) return false;
        action.run();
        return true;
    }

    /** The caller must also validate the decision schema and current step preconditions. */
    public boolean accepts(ProposalFence fence) {
        requireThread();
        Objects.requireNonNull(fence, "fence");
        return !companion.stopLatched()
                && companion.lifecycle() != LifecycleState.FAULTED
                && companion.lifecycle() != LifecycleState.REMOVED
                && sessionEpoch.equals(fence.sessionEpoch())
                && companion.intentRevision() == fence.intentRevision()
                && companion.taskRevision() == fence.taskRevision()
                && clock.instant().isBefore(fence.deadline());
    }

    private ActionLease begin(UUID taskId, UUID actionId, Duration timeout, boolean direct) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(actionId, "actionId");
        long timeoutNanos = positiveNanos(timeout);
        requireUsable();
        if (companion.stopLatched()) throw rejected(Reason.STOPPED, "Resume this companion first");
        settleCancellation();
        requireUsable();
        if (cancelling != null) {
            throw rejected(Reason.CANCELLATION_PENDING, "The previous action is still settling");
        }
        if (active != null) throw rejected(Reason.BUSY, "This companion already has an active skill");
        long intent = direct ? Math.incrementExact(companion.intentRevision()) : companion.intentRevision();
        companion = companion.withRevisions(intent, Math.incrementExact(companion.taskRevision()))
                .withLifecycle(LifecycleState.ACTIVE);
        bodyGeneration = Math.incrementExact(bodyGeneration);
        ActionLease lease = new ActionLease(companion.id(), taskId, actionId, sessionEpoch,
                bodyGeneration, companion.intentRevision(), companion.taskRevision());
        active = lease;
        activeSince = nanoTime.getAsLong();
        activeTimeoutNanos = timeoutNanos;
        try {
            adapter.start(lease);
        } catch (RuntimeException failure) {
            companion = companion.withLifecycle(LifecycleState.FAULTED);
            bodyGeneration = Math.incrementExact(bodyGeneration);
            cancelActive();
            throw new IllegalStateException("Body adapter failed to start; companion faulted", failure);
        }
        return lease;
    }

    private void cancelActive() {
        if (active == null) return;
        cancelling = active;
        active = null; // Revoke authority before invoking potentially reentrant adapter code.
        cancellationSince = nanoTime.getAsLong();
        try {
            adapter.cancel(cancelling);
        } catch (RuntimeException failure) {
            companion = companion.withLifecycle(LifecycleState.FAULTED);
        }
    }

    private void settleCancellation() {
        if (cancelling == null) return;
        try {
            if (adapter.isSettled(cancelling)) {
                cancelling = null;
            } else if (elapsed(cancellationSince) >= cancellationTimeoutNanos) {
                companion = companion.withLifecycle(LifecycleState.FAULTED);
            }
        } catch (RuntimeException failure) {
            companion = companion.withLifecycle(LifecycleState.FAULTED);
        }
    }

    private void requireUsable() {
        if (companion.lifecycle() == LifecycleState.FAULTED) {
            throw rejected(Reason.FAULTED, "This companion needs operator recovery");
        }
        if (companion.lifecycle() == LifecycleState.REMOVED) {
            throw rejected(Reason.REMOVED, "This companion has been removed");
        }
    }

    private void requireThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Body authority is restricted to the owning server thread");
        }
    }

    private long elapsed(long since) {
        return nanoTime.getAsLong() - since;
    }

    private static long positiveNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        return duration.toNanos();
    }

    private static ControlRejectedException rejected(Reason reason, String message) {
        return new ControlRejectedException(reason, message);
    }
}
