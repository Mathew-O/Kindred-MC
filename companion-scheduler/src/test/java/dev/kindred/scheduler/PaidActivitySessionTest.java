package dev.kindred.scheduler;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class PaidActivitySessionTest {
    @Test void durableAcknowledgementAndOwnerDispatchPrecedeActivation() {
        Harness h = new Harness();
        var request = h.request();
        assertFalse(h.session.paid());
        h.time.advance(250);
        h.writer.ack();
        assertFalse(h.session.paid()); // IO completion alone never mutates owner-thread state.
        h.pump();
        assertTrue(request.join());
        assertTrue(h.session.paid());
        h.time.advance(3);
        h.session.stop();
        assertFalse(h.session.paid());
        h.writer.ack(); h.pump();
        assertEquals(3, h.session.snapshot().chargedMillis());
        assertEquals(1, h.writer.calls.get(1).expected);
    }

    @Test void stopBeforeAcknowledgementFencesTheReservationAndSettlesUnusedTime() {
        Harness h = new Harness();
        var request = h.request();
        h.session.stop();
        assertFalse(request.join());
        h.writer.ack(); h.pump();
        assertFalse(h.session.paid());
        assertEquals(PaidActivitySession.Phase.SETTLING, h.session.phase());
        h.writer.ack(); h.pump();
        assertEquals(PaidActivitySession.Phase.IDLE, h.session.phase());
        assertEquals(0, h.session.snapshot().chargedMillis());
        assertTrue(h.session.snapshot().outstanding().isEmpty());
    }

    @Test void expiredAcknowledgementNeverStartsWork() {
        Harness h = new Harness();
        var request = h.request();
        h.time.advance(6000);
        h.writer.ack(); h.pump();
        assertFalse(request.join());
        assertFalse(h.session.paid());
        h.writer.ack(); h.pump();
        assertEquals(0, h.session.snapshot().chargedMillis());
    }

    @Test void activeExpiryRevokesImmediatelyAndNeverRenewsItself() {
        Harness h = new Harness();
        h.request(); h.writer.ack(); h.pump();
        h.time.advance(5000);
        assertFalse(h.session.paid());
        assertEquals(PaidActivitySession.Phase.SETTLING, h.session.phase());
        h.writer.ack(); h.pump();
        assertEquals(5000, h.session.snapshot().chargedMillis());
        assertEquals(2, h.writer.calls.size());
        assertFalse(h.session.paid());
    }

    @Test void ambiguousMeasurementRetainsAllPrepaymentWithoutInventedObservation() {
        Harness h = new Harness();
        h.request(); h.writer.ack(); h.pump();
        h.time.nano = -1;
        assertFalse(h.session.paid());
        h.writer.ack(); h.pump();
        assertEquals(5000, h.session.snapshot().chargedMillis());
        assertEquals(0, h.session.snapshot().observedActiveMillis());
    }

    @Test void failedOrMismatchedAcknowledgementBlocksWithoutRefundOrRetry() {
        for (boolean mismatch : List.of(false, true)) {
            Harness h = new Harness();
            var request = h.request();
            Call first = h.writer.calls.getFirst();
            if (mismatch) {
                var expected = first.next;
                first.result.complete(new BudgetState(UUID.randomUUID(), expected.period(), 0, 0,
                        expected.sequence(), expected.outstanding()));
            } else first.result.completeExceptionally(new IllegalStateException("Unknown commit outcome"));
            h.pump();
            assertTrue(request.isCompletedExceptionally());
            assertEquals(PaidActivitySession.Phase.BLOCKED, h.session.phase());
            assertFalse(h.session.paid());
            assertFalse(h.request().join());
            assertThrows(CompletionException.class, () -> h.session.close().join());
            assertEquals(1, h.writer.calls.size());
        }
    }

    @Test void pendingShutdownSettlesUsingOnlyIoContinuationsWithoutOwnerTicks() {
        Harness h = new Harness();
        var request = h.request();
        var closed = h.session.close();
        assertFalse(request.join());
        h.writer.ack(); // Reserve acknowledgement; no owner queue pumping.
        assertEquals(2, h.writer.calls.size());
        h.writer.ack();
        BudgetState finalState = closed.join();
        assertTrue(finalState.outstanding().isEmpty());
        assertEquals(0, finalState.chargedMillis());
        assertEquals(PaidActivitySession.Phase.CLOSED, h.session.phase());
        h.pump();
        assertFalse(h.session.paid());
    }

    @Test void activeAndAlreadySettlingShutdownCannotRefundOrSettleTwice() {
        for (boolean alreadySettling : List.of(false, true)) {
            Harness h = new Harness();
            h.request(); h.writer.ack(); h.pump();
            h.time.advance(7);
            if (alreadySettling) h.session.stop();
            var closed = h.session.close();
            assertSame(closed, h.session.close());
            h.writer.ack();
            assertEquals(7, closed.join().chargedMillis());
            assertEquals(7, closed.join().observedActiveMillis());
            assertEquals(2, h.writer.calls.size());
            h.pump();
            assertEquals(PaidActivitySession.Phase.CLOSED, h.session.phase());
        }
    }

    @Test void exhaustedOrUnrecoveredBudgetsCannotAcquireNewTime() {
        Harness h = new Harness();
        BudgetState exhausted = new BudgetState(h.initial.companionId(), h.initial.period(), 1000, 1000, 0, Optional.empty());
        var session = new PaidActivitySession(exhausted, Duration.ofSeconds(1), h.writer, h.queue::add, h.time, () -> h.time.nano);
        assertFalse(session.request(h.time.wall.plusSeconds(10)).join());
        assertTrue(h.writer.calls.isEmpty());
        var outstanding = h.initial.reserve(UUID.randomUUID(), Duration.ofSeconds(1), Duration.ofHours(2), h.time.wall, h.time.wall.plusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> new PaidActivitySession(outstanding,
                Duration.ofHours(2), h.writer, h.queue::add, h.time, () -> h.time.nano));
    }

    @Test void workerCannotReadOrChangeOwnerThreadSessionState() {
        Harness h = new Harness();
        var failed = CompletableFuture.supplyAsync(h.session::paid);
        assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, failed::join).getCause());
        assertEquals(PaidActivitySession.Phase.IDLE, h.session.phase());
    }

    private static final class Harness {
        final Time time = new Time();
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final FakeWriter writer = new FakeWriter();
        final BudgetState initial = BudgetState.empty(UUID.randomUUID(), AccountingPeriod.containing(time.wall, ZoneOffset.UTC));
        final PaidActivitySession session = new PaidActivitySession(initial, Duration.ofHours(2), writer, queue::add, time, () -> time.nano);
        CompletableFuture<Boolean> request() { return session.request(time.wall.plusSeconds(10)); }
        void pump() { while (!queue.isEmpty()) queue.remove().run(); }
    }
    private record Call(BudgetState next, long expected, CompletableFuture<BudgetState> result) { }
    private static final class FakeWriter implements PaidActivitySession.Writer {
        final List<Call> calls = new ArrayList<>();
        int ackIndex;
        @Override public CompletableFuture<BudgetState> save(BudgetState next, long expected) {
            var result = new CompletableFuture<BudgetState>();
            calls.add(new Call(next, expected, result));
            return result;
        }
        void ack() { Call call = calls.get(ackIndex++); call.result.complete(call.next); }
    }
    private static final class Time extends Clock {
        Instant wall = Instant.parse("2026-10-15T12:00:00Z");
        long nano;
        void advance(long millis) { wall = wall.plusMillis(millis); nano += millis * 1_000_000; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return wall; }
    }
}
