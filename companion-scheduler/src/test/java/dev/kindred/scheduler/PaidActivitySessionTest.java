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

/**
 * In plain terms: checks a stretch of play time: asking for more, renewing in time, and stopping.
 */
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

    @Test void renewalSettlesTheActiveSliceAndChargesTheWriteLatencyToTheNext() {
        Harness h = new Harness();
        h.request(); h.writer.ack(); h.pump();
        h.time.advance(4000);
        var renewed = h.renew();
        assertEquals(2, h.writer.calls.size());
        assertEquals(1, h.writer.calls.get(1).expected);
        assertTrue(h.session.renewing());
        assertTrue(h.session.paid()); // The active permit governs until the renewal is acknowledged.
        assertFalse(h.renew().join()); // One renewal at a time.
        assertEquals(2, h.writer.calls.size());
        h.time.advance(30);
        h.writer.ack(); h.pump();
        assertTrue(renewed.join());
        assertFalse(h.session.renewing());
        assertEquals(4000, h.session.snapshot().chargedMillis());
        assertEquals(5000, h.session.snapshot().outstanding().orElseThrow().reservedMillis());
        // The new slice counts from the request: the 30 ms the write took are charged and come off its end.
        h.time.advance(4930);
        assertTrue(h.session.paid());
        h.time.advance(20);
        assertFalse(h.session.paid());
        h.writer.ack(); h.pump();
        assertEquals(PaidActivitySession.Phase.IDLE, h.session.phase());
        assertEquals(4000 + 4970, h.session.snapshot().chargedMillis());
        assertEquals(4000 + 4970, h.session.snapshot().observedActiveMillis());
        assertTrue(h.session.snapshot().outstanding().isEmpty());
    }

    @Test void stopOrLapseWhileRenewingChargesTheNewSliceOnlyUntilThen() {
        for (boolean lapse : List.of(false, true)) {
            Harness h = new Harness();
            h.request(); h.writer.ack(); h.pump();
            h.time.advance(4000);
            var renewed = h.renew();
            h.time.advance(lapse ? 1000 : 10);
            if (lapse) assertFalse(h.session.paid()); // The old permit lapsed before the acknowledgement.
            else h.session.stop();
            assertFalse(renewed.join());
            assertFalse(h.session.paid());
            assertEquals(2, h.writer.calls.size()); // Nothing written over the renewal in flight.
            h.writer.ack(); h.pump();
            assertFalse(h.session.paid());
            assertEquals(3, h.writer.calls.size());
            h.writer.ack(); h.pump();
            assertEquals(PaidActivitySession.Phase.IDLE, h.session.phase());
            assertEquals(4000 + (lapse ? 1000 : 10), h.session.snapshot().chargedMillis());
            assertTrue(h.session.snapshot().outstanding().isEmpty());
        }
    }

    @Test void closeWhileRenewingSettlesTheNewSliceWithoutOwnerTicks() {
        Harness h = new Harness();
        h.request(); h.writer.ack(); h.pump();
        h.time.advance(4000);
        var renewed = h.renew();
        h.time.advance(25);
        var closed = h.session.close();
        assertFalse(renewed.join());
        h.writer.ack(); // The renewal; no owner queue pumping.
        assertEquals(3, h.writer.calls.size());
        h.writer.ack();
        BudgetState finalState = closed.join();
        assertEquals(4025, finalState.chargedMillis());
        assertTrue(finalState.outstanding().isEmpty());
        h.pump();
        assertEquals(PaidActivitySession.Phase.CLOSED, h.session.phase());
    }

    @Test void aFailedRenewalWriteBlocksAndRevokes() {
        Harness h = new Harness();
        h.request(); h.writer.ack(); h.pump();
        h.time.advance(4000);
        var renewed = h.renew();
        h.writer.calls.get(1).result.completeExceptionally(new IllegalStateException("Unknown commit outcome"));
        h.pump();
        assertTrue(renewed.isCompletedExceptionally());
        assertEquals(PaidActivitySession.Phase.BLOCKED, h.session.phase());
        assertFalse(h.session.paid());
        assertFalse(h.renew().join());
        assertEquals(2, h.writer.calls.size());
    }

    @Test void renewalStaysWithinTheCapAndStopsOnceItIsSpent() {
        Harness h = new Harness();
        var session = new PaidActivitySession(h.initial, Duration.ofSeconds(6), h.writer, h.queue::add, h.time, () -> h.time.nano);
        session.request(h.time.wall.plusSeconds(60)); h.writer.ack(); h.pump();
        h.time.advance(4000);
        assertTrue(renewNow(h, session));
        assertEquals(2000, session.snapshot().outstanding().orElseThrow().reservedMillis()); // Only what the cap has left.
        h.time.advance(2000);
        assertFalse(session.paid());
        h.writer.ack(); h.pump();
        assertEquals(6000, session.snapshot().chargedMillis());
        assertFalse(session.renew(h.time.wall.plusSeconds(60)).join());
        assertFalse(session.request(h.time.wall.plusSeconds(60)).join());
        assertEquals(3, h.writer.calls.size()); // Nothing more was reserved.
    }

    private static boolean renewNow(Harness h, PaidActivitySession session) {
        var renewed = session.renew(h.time.wall.plusSeconds(60));
        h.writer.ack(); h.pump();
        return renewed.join();
    }

    @Test void noRenewalPastTheWindowOrForALapsedPermit() {
        Harness h = new Harness();
        assertFalse(h.renew().join()); // Nothing active.
        h.request(); h.writer.ack(); h.pump();
        h.time.advance(4000);
        assertFalse(h.session.renew(h.time.wall).join()); // The eligible window ends now: no time to grant.
        assertEquals(1, h.writer.calls.size());
        assertTrue(h.session.paid());
        assertEquals(Duration.ofMillis(1000), h.session.remaining());
        h.time.advance(1000);
        assertFalse(h.renew().join());
        assertFalse(h.session.paid());
        assertEquals(Duration.ZERO, h.session.remaining());
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
        CompletableFuture<Boolean> renew() { return session.renew(time.wall.plusSeconds(60)); }
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
