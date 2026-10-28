package dev.kindred.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the play-time budget for one day: what is used, set aside and left.
 */
class BudgetStateTest {
    private static final Instant NOW = Instant.parse("2026-10-13T18:00:00Z");
    private static final Duration CAP = Duration.ofMinutes(2);
    private static final AccountingPeriod PERIOD = AccountingPeriod.containing(NOW, ZoneId.of("America/New_York"));

    @Test
    void reservationIsLimitedToFiveSecondsAndAlreadyUnavailable() {
        BudgetState state = empty().reserve(UUID.randomUUID(), Duration.ofMinutes(1), CAP, NOW, PERIOD.endExclusive());
        assertEquals(5_000, state.outstanding().orElseThrow().reservedMillis());
        assertEquals(Duration.ofSeconds(115), state.remainingAllowance(CAP));
        assertEquals(0, state.chargedMillis());
        assertThrows(IllegalStateException.class,
                () -> state.reserve(UUID.randomUUID(), Duration.ofSeconds(1), CAP, NOW, PERIOD.endExclusive()));
    }

    @Test
    void cleanPauseChargesObservedTimeAndReleasesUnusedPart() {
        UUID id = UUID.randomUUID();
        BudgetState state = empty().reserve(id, Duration.ofSeconds(5), CAP, NOW, PERIOD.endExclusive())
                .settle(id, Duration.ofMillis(1_234));
        assertEquals(1_234, state.chargedMillis());
        assertEquals(1_234, state.observedActiveMillis());
        assertEquals(Duration.ofMillis(118_766), state.remainingAllowance(CAP));
        assertTrue(state.outstanding().isEmpty());
    }

    @Test
    void restartConservativelyChargesOutstandingSliceOnceWithoutInventingActivity() {
        UUID id = UUID.randomUUID();
        BudgetState beforeCrash = empty().reserve(id, Duration.ofSeconds(5), CAP, NOW, PERIOD.endExclusive());
        // Rehydrate the persisted record as a new process would, then recover.
        BudgetState loaded = new BudgetState(beforeCrash.companionId(), beforeCrash.period(), beforeCrash.chargedMillis(),
                beforeCrash.observedActiveMillis(), beforeCrash.sequence(), beforeCrash.outstanding());
        BudgetState recovered = loaded.recoverAfterCrash();
        assertEquals(5_000, recovered.chargedMillis());
        assertEquals(0, recovered.observedActiveMillis());
        assertEquals(Duration.ofSeconds(115), recovered.remainingAllowance(CAP));
        assertEquals(recovered, recovered.recoverAfterCrash());
        assertThrows(IllegalStateException.class, () -> recovered.settle(id, Duration.ZERO));
    }

    @Test
    void remainingCapAndWindowAndMidnightEachBoundReservation() {
        BudgetState nearlyExhausted = new BudgetState(UUID.randomUUID(), PERIOD, 119_500, 119_500, 2, Optional.empty());
        assertEquals(500, nearlyExhausted.reserve(UUID.randomUUID(), Duration.ofSeconds(5), CAP, NOW,
                PERIOD.endExclusive()).outstanding().orElseThrow().reservedMillis());
        assertEquals(300, empty().reserve(UUID.randomUUID(), Duration.ofSeconds(5), CAP, NOW,
                NOW.plusMillis(300)).outstanding().orElseThrow().reservedMillis());
        Instant beforeMidnight = PERIOD.endExclusive().minusMillis(200);
        assertEquals(200, empty().reserve(UUID.randomUUID(), Duration.ofSeconds(5), CAP, beforeMidnight,
                PERIOD.endExclusive().plusSeconds(60)).outstanding().orElseThrow().reservedMillis());
    }

    @Test
    void capEditsNeverEraseUsageAndLowerCapPreventsNewWork() {
        BudgetState state = new BudgetState(UUID.randomUUID(), PERIOD, 10_000, 10_000, 2, Optional.empty());
        Duration lowered = Duration.ofSeconds(8);
        assertEquals(Duration.ZERO, state.remainingAllowance(lowered));
        assertSame(state, state.reserve(UUID.randomUUID(), Duration.ofSeconds(5), lowered, NOW, PERIOD.endExclusive()));
        assertEquals(Duration.ofSeconds(10), state.remainingAllowance(Duration.ofSeconds(20)));
        assertEquals(10_000, state.chargedMillis());
    }

    @Test
    void atomicRenewalCannotOverbookAndStaleSettlementCannotTouchReplacement() {
        UUID first = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        Duration cap = Duration.ofSeconds(6);
        BudgetState initial = empty().reserve(first, Duration.ofSeconds(5), cap, NOW, PERIOD.endExclusive());
        BudgetState renewed = initial.renew(first, Duration.ofSeconds(4), next, Duration.ofSeconds(5),
                cap, NOW.plusSeconds(4), PERIOD.endExclusive());
        assertEquals(4_000, renewed.chargedMillis());
        assertEquals(2_000, renewed.outstanding().orElseThrow().reservedMillis());
        assertEquals(Duration.ZERO, renewed.remainingAllowance(cap));
        assertTrue(renewed.sequence() > initial.sequence());
        assertThrows(IllegalStateException.class, () -> renewed.settle(first, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> renewed.settle(next, Duration.ofSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> initial.renew(first, Duration.ZERO, first,
                Duration.ofSeconds(5), cap, NOW, PERIOD.endExclusive()));
    }

    @Test
    void noWorkIsReservedAtDeadlineOrOutsideTheCurrentPeriod() {
        BudgetState state = empty();
        assertSame(state, state.reserve(UUID.randomUUID(), Duration.ofSeconds(5), CAP, NOW, NOW));
        assertSame(state, state.reserve(UUID.randomUUID(), Duration.ofSeconds(5), CAP,
                PERIOD.endExclusive(), PERIOD.endExclusive().plusSeconds(5)));
        assertSame(state, state.reserve(UUID.randomUUID(), Duration.ofSeconds(5), CAP,
                PERIOD.startInclusive().minusMillis(1), PERIOD.endExclusive()));
    }

    @Test
    void downtimeDoesNotChargeTheOfflineIntervalOrSupplyANewReservation() {
        UUID id = UUID.randomUUID();
        BudgetState prepaid = empty().reserve(id, Duration.ofSeconds(5), CAP, NOW, PERIOD.endExclusive());
        assertEquals(Duration.ZERO, prepaid.outstanding().orElseThrow().availableAt(NOW.plus(Duration.ofHours(1))));
        BudgetState recovered = prepaid.recoverAfterCrash();
        assertEquals(5_000, recovered.chargedMillis());
        assertTrue(recovered.outstanding().isEmpty());
    }

    @Test
    void invalidPersistedReservationCannotCrossPeriodsOrOverstateSlice() {
        assertThrows(IllegalArgumentException.class,
                () -> new Reservation(UUID.randomUUID(), 5_001, NOW, NOW.plusMillis(5_001)));
        Reservation acrossMidnight = new Reservation(UUID.randomUUID(), 5_000,
                PERIOD.endExclusive().minusSeconds(1), PERIOD.endExclusive().plusSeconds(4));
        assertThrows(IllegalArgumentException.class, () -> new BudgetState(UUID.randomUUID(), PERIOD,
                0, 0, 0, Optional.of(acrossMidnight)));
    }

    private static BudgetState empty() {
        return BudgetState.empty(UUID.randomUUID(), PERIOD);
    }
}
