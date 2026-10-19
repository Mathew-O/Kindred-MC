package dev.kindred.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static dev.kindred.core.ControlRejectedException.Reason;
import static org.junit.jupiter.api.Assertions.*;

class ActionArbiterTest {
    private final UUID ownerId = UUID.randomUUID();
    private final Actor owner = Actor.player(ownerId);
    private final Companion companion = Companion.create(UUID.randomUUID(), ownerId,
            "Juniper", UUID.randomUUID(), "minecraft:overworld");
    private final FakeTime time = new FakeTime();
    private final FakeBody body = new FakeBody();
    private final ActionArbiter arbiter = arbiter(companion);

    @Test
    void unauthorizedControlCannotChangeStateOrInvokeAdapter() {
        Actor stranger = Actor.player(UUID.randomUUID());
        Companion before = arbiter.snapshot();
        assertThrows(SecurityException.class, () -> acquire(stranger));
        assertThrows(SecurityException.class, () -> arbiter.stop(stranger));
        assertThrows(SecurityException.class, () -> arbiter.resume(stranger));
        assertEquals(before, arbiter.snapshot());
        assertTrue(body.started.isEmpty());
        assertTrue(body.cancelled.isEmpty());
        assertTrue(arbiter.isCurrent(acquire(Actor.console())));
    }

    @Test
    void stopRevokesBeforeAdapterCancelAndRepeatedStopCannotRestart() {
        ActionLease lease = acquire(owner);
        AtomicInteger staleActions = new AtomicInteger();
        body.onCancel = () -> assertFalse(arbiter.executeIfCurrent(lease, staleActions::incrementAndGet));
        arbiter.stop(owner);
        arbiter.stop(owner);
        assertFalse(arbiter.isCurrent(lease));
        assertEquals(0, staleActions.get());
        assertEquals(List.of(lease), body.cancelled);
        assertEquals(LifecycleState.PAUSED, arbiter.snapshot().lifecycle());
        assertTrue(arbiter.snapshot().stopLatched());
        assertReason(Reason.STOPPED, () -> acquire(owner));
        assertReason(Reason.STOPPED, () -> arbiter.acquireAutonomous(UUID.randomUUID(),
                UUID.randomUUID(), Duration.ofSeconds(10)));
    }

    @Test
    void resumeCannotOverlapUnsettledCancellationAndOldCallbacksStayRevoked() {
        ActionLease old = acquire(owner);
        arbiter.stop(owner);
        arbiter.resume(owner);
        assertReason(Reason.CANCELLATION_PENDING, () -> acquire(owner));
        body.settled = true;
        arbiter.tick();
        ActionLease replacement = acquire(owner);
        assertTrue(replacement.bodyGeneration() > old.bodyGeneration());
        assertFalse(arbiter.executeIfCurrent(old, () -> fail("Stale callback executed")));
        assertTrue(arbiter.isCurrent(replacement));
        assertEquals(2, body.started.size());
    }

    @Test
    void hungCancellationFaultsAndCannotBeRecoveredByResume() {
        acquire(owner);
        arbiter.stop(owner);
        arbiter.resume(owner);
        time.advance(Duration.ofMillis(500));
        arbiter.tick();
        assertEquals(LifecycleState.FAULTED, arbiter.snapshot().lifecycle());
        assertReason(Reason.FAULTED, () -> acquire(owner));
        assertReason(Reason.FAULTED, () -> arbiter.resume(owner));
        body.settled = true;
        arbiter.tick();
        assertReason(Reason.FAULTED, () -> acquire(owner));
        assertEquals(1, body.started.size());
    }

    @Test
    void proposalRequiresEpochRevisionsAndUnexpiredDeadline() {
        ProposalFence initial = fence(time.instant().plusSeconds(8));
        assertTrue(arbiter.accepts(initial));
        assertFalse(arbiter.accepts(new ProposalFence(UUID.randomUUID(), 0, 0, initial.deadline())));
        assertFalse(arbiter.accepts(new ProposalFence(arbiter.sessionEpoch(), 1, 0, initial.deadline())));
        assertFalse(arbiter.accepts(new ProposalFence(arbiter.sessionEpoch(), 0, 1, initial.deadline())));
        assertFalse(arbiter.accepts(fence(time.instant())));
        acquire(owner);
        assertFalse(arbiter.accepts(initial));
        ProposalFence current = fence(time.instant().plusSeconds(8));
        assertTrue(arbiter.accepts(current));
        arbiter.stop(owner);
        assertFalse(arbiter.accepts(current));
        arbiter.resume(owner);
        assertFalse(arbiter.accepts(current));
    }

    @Test
    void restartPreservesStopAndInvalidatesPreviousEpoch() {
        ActionLease lease = acquire(owner);
        ProposalFence previous = fence(time.instant().plusSeconds(8));
        arbiter.stop(owner);
        ActionArbiter restarted = arbiter(arbiter.snapshot());
        assertTrue(restarted.snapshot().stopLatched());
        assertFalse(restarted.accepts(previous));
        assertFalse(restarted.isCurrent(lease));
        assertReason(Reason.STOPPED, () -> restarted.acquireAutonomous(UUID.randomUUID(),
                UUID.randomUUID(), Duration.ofSeconds(10)));
    }

    @Test
    void longTickGapRevokesTimedOutActionBeforeNewMutation() {
        ActionLease lease = arbiter.acquire(owner, UUID.randomUUID(), UUID.randomUUID(), Duration.ofSeconds(1));
        time.advance(Duration.ofHours(1));
        assertFalse(arbiter.executeIfCurrent(lease, () -> fail("Expired action executed")));
        arbiter.tick();
        assertEquals(List.of(lease), body.cancelled);
        assertEquals(LifecycleState.BLOCKED, arbiter.snapshot().lifecycle());
        assertReason(Reason.CANCELLATION_PENDING, () -> acquire(owner));
    }

    @Test
    void normalCompletionRequiresSettledBodyAndRejectsRepeatedCompletion() {
        ActionLease lease = acquire(owner);
        assertFalse(arbiter.complete(lease));
        assertReason(Reason.BUSY, () -> acquire(owner));
        body.settled = true;
        assertTrue(arbiter.complete(lease));
        assertFalse(arbiter.complete(lease));
        assertFalse(arbiter.isCurrent(lease));
        assertEquals(LifecycleState.WAITING, arbiter.snapshot().lifecycle());
        assertTrue(arbiter.isCurrent(acquire(owner)));
    }

    @Test
    void redundantResumeDoesNotInvalidateAnActiveLease() {
        ActionLease lease = acquire(owner);
        arbiter.resume(owner);
        assertTrue(arbiter.isCurrent(lease));
    }

    @Test
    void wrongThreadCannotDispatchWorldActions() throws InterruptedException {
        ActionLease lease = acquire(owner);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                arbiter.executeIfCurrent(lease, () -> fail("Worker touched body"));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        worker.start();
        worker.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(arbiter.isCurrent(lease));
    }

    @Test
    void adapterExceptionsFaultWithoutGrantingReplacementAuthority() {
        body.onCancel = () -> { throw new IllegalStateException("Broken cancellation"); };
        ActionLease lease = acquire(owner);
        arbiter.stop(owner);
        assertFalse(arbiter.isCurrent(lease));
        assertEquals(LifecycleState.FAULTED, arbiter.snapshot().lifecycle());
        assertReason(Reason.FAULTED, () -> acquire(owner));
    }

    private ActionArbiter arbiter(Companion state) {
        return new ActionArbiter(state, UUID.randomUUID(), body, time, () -> time.nanos,
                Duration.ofMillis(500));
    }

    @Test
    void hazardCancellationRevokesLeaseAndSettlesBeforeNewController() {
        ActionLease lease = acquire(owner);
        assertTrue(arbiter.isCurrent(lease));
        arbiter.cancel(lease);
        assertFalse(arbiter.isCurrent(lease));
        assertTrue(arbiter.cancellationPending());
        assertReason(Reason.CANCELLATION_PENDING, () -> acquire(owner));
        AtomicInteger executed = new AtomicInteger();
        assertFalse(arbiter.executeIfCurrent(lease, executed::incrementAndGet));
        assertEquals(0, executed.get());
        body.settled = true;
        arbiter.tick();
        assertFalse(arbiter.cancellationPending());
        ActionLease retreatLease = acquire(owner);
        assertTrue(arbiter.isCurrent(retreatLease));
        assertFalse(arbiter.isCurrent(lease));
    }

    private ActionLease acquire(Actor actor) {
        return arbiter.acquire(actor, UUID.randomUUID(), UUID.randomUUID(), Duration.ofSeconds(10));
    }

    private ProposalFence fence(Instant deadline) {
        Companion current = arbiter.snapshot();
        return new ProposalFence(arbiter.sessionEpoch(), current.intentRevision(), current.taskRevision(), deadline);
    }

    private static void assertReason(Reason reason, Runnable action) {
        assertEquals(reason, assertThrows(ControlRejectedException.class, action::run).reason());
    }

    private static final class FakeBody implements BodyAdapter {
        final List<ActionLease> started = new ArrayList<>();
        final List<ActionLease> cancelled = new ArrayList<>();
        boolean settled;
        Runnable onCancel = () -> { };

        @Override public void start(ActionLease lease) { started.add(lease); }
        @Override public void cancel(ActionLease lease) { cancelled.add(lease); onCancel.run(); }
        @Override public boolean isSettled(ActionLease lease) { return settled; }
    }

    private static final class FakeTime extends Clock {
        private Instant current = Instant.parse("2026-10-13T12:00:00Z");
        private long nanos;

        void advance(Duration duration) { current = current.plus(duration); nanos += duration.toNanos(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        @Override public Instant instant() { return current; }
    }
}
