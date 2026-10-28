package dev.kindred.scheduler;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the permits that let a player be active for a few seconds at a time.
 */
class ActivityPermitTest {
    private final UUID player = UUID.randomUUID();
    private final UUID reservation = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-10-15T12:00:00Z");
    private final BudgetState reserved = BudgetState.empty(player, AccountingPeriod.containing(start, ZoneOffset.UTC))
            .reserve(reservation, Duration.ofSeconds(5), Duration.ofHours(2), start, start.plusSeconds(5));

    @Test void earlyExpiredAndSubMillisecondAcknowledgementsGrantNoTime() {
        assertTrue(ActivityPermit.acknowledged(reserved, reservation, start.minusMillis(1), 0).isEmpty());
        assertTrue(ActivityPermit.acknowledged(reserved, reservation, start.plusSeconds(5), 0).isEmpty());
        assertTrue(ActivityPermit.acknowledged(reserved, reservation, start.plusSeconds(5).minusNanos(1), 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ActivityPermit.acknowledged(reserved, UUID.randomUUID(), start, 0));
    }

    @Test void acknowledgementLatencyReducesTheMonotonicGrant() {
        var permit = ActivityPermit.acknowledged(reserved, reservation, start.plusMillis(250), 100).orElseThrow();
        assertEquals(Duration.ofMillis(4750), permit.maximumActiveTime());
        assertTrue(permit.allows(player, start.plusMillis(251), 1_000_100));
        assertFalse(permit.allows(player, start.plusSeconds(1), 4_750_000_100L));
        assertFalse(permit.allows(UUID.randomUUID(), start.plusMillis(251), 1_000_100));
    }

    @Test void wallAndMonotonicFailuresCannotExtendTheReservation() {
        var permit = ActivityPermit.acknowledged(reserved, reservation, start.plusMillis(100), 500).orElseThrow();
        assertFalse(permit.allows(player, start.plusSeconds(5), 600));
        assertFalse(permit.allows(player, start.plusMillis(99), 600));
        assertFalse(permit.allows(player, start.plusMillis(101), 499));
        assertFalse(permit.allows(player, start.plusMillis(101), 4_900_000_500L));
    }

    @Test void measuredTimeRoundsUpAndNeverExceedsTheRemainingPaidSlice() {
        var permit = ActivityPermit.acknowledged(reserved, reservation, start.plusMillis(250), 0).orElseThrow();
        assertEquals(Duration.ofMillis(3), permit.observedUntil(2_000_001).orElseThrow());
        assertEquals(Duration.ofMillis(4750), permit.observedUntil(60_000_000_000L).orElseThrow());
        var settled = permit.settle(reserved, 2_000_001);
        assertEquals(3, settled.chargedMillis());
        assertEquals(3, settled.observedActiveMillis());
        assertTrue(settled.outstanding().isEmpty());
    }

    @Test void ambiguousMonotonicAccountingRetainsThePrepaidAmountWithoutInventingObservation() {
        var permit = ActivityPermit.acknowledged(reserved, reservation, start, 100).orElseThrow();
        assertTrue(permit.observedUntil(99).isEmpty());
        var settled = permit.settle(reserved, 99);
        assertEquals(5000, settled.chargedMillis());
        assertEquals(0, settled.observedActiveMillis());
        assertTrue(settled.outstanding().isEmpty());
    }

    @Test void settlementRejectsForeignChangedAndAlreadySettledBudgetStates() {
        var permit = ActivityPermit.acknowledged(reserved, reservation, start, 0).orElseThrow();
        var foreign = new BudgetState(UUID.randomUUID(), reserved.period(), 0, 0, reserved.sequence(), reserved.outstanding());
        var changed = new BudgetState(player, reserved.period(), 0, 0, reserved.sequence() + 1, reserved.outstanding());
        assertThrows(IllegalArgumentException.class, () -> permit.settle(foreign, 1));
        assertThrows(IllegalArgumentException.class, () -> permit.settle(changed, 1));
        Instant nextDay = start.plusSeconds(86400);
        var otherPeriod = BudgetState.empty(player, AccountingPeriod.containing(nextDay, ZoneOffset.UTC))
                .reserve(reservation, Duration.ofSeconds(5), Duration.ofHours(2), nextDay, nextDay.plusSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> permit.settle(otherPeriod, 1));
        assertThrows(IllegalArgumentException.class, () -> permit.settle(reserved.settle(reservation, Duration.ZERO), 1));
    }

    @Test void normalSignedNanoTimeWrapDoesNotCreateAnUnlimitedPermit() {
        long nanoStart = Long.MAX_VALUE - 500_000;
        var permit = ActivityPermit.acknowledged(reserved, reservation, start, nanoStart).orElseThrow();
        assertTrue(permit.allows(player, start.plusMillis(1), nanoStart + 1_000_000));
        assertEquals(Duration.ofMillis(1), permit.observedUntil(nanoStart + 1_000_000).orElseThrow());
        assertFalse(permit.allows(player, start.plusSeconds(1), nanoStart + 5_000_000_000L));
    }
}
