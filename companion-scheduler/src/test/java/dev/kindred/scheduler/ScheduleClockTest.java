package dev.kindred.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleClockTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void defaultScheduleIsTwoHoursWithoutOfflineWorkOrWindow() {
        ActivitySchedule schedule = ActivitySchedule.defaults(NEW_YORK, Duration.ofHours(4));
        assertEquals(Duration.ofHours(2), schedule.dailyCap());
        assertTrue(schedule.window().isEmpty());
        assertFalse(schedule.workWhenOwnerOffline());
        assertEquals(Duration.ofMinutes(30), ActivitySchedule.defaults(NEW_YORK, Duration.ofMinutes(30)).dailyCap());
    }

    @Test
    void rejectsEqualEndpointsAndInvalidOrExcessiveCaps() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActivityWindow(LocalTime.NOON, LocalTime.NOON));
        ActivitySchedule schedule = ActivitySchedule.defaults(NEW_YORK, Duration.ofHours(4));
        assertThrows(IllegalArgumentException.class, () -> schedule.withDailyCap(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> schedule.withDailyCap(Duration.ofHours(5)));
        assertThrows(IllegalArgumentException.class, () -> schedule.withDailyCap(Duration.ofNanos(1)));
    }

    @Test
    void ordinaryWindowIncludesStartAndExcludesEnd() {
        ActivitySchedule schedule = schedule(LocalTime.of(14, 0), LocalTime.of(22, 0));
        AccountingPeriod period = AccountingPeriod.on(LocalDate.of(2026, 10, 13), NEW_YORK);
        assertTrue(ScheduleClock.activeInterval(schedule, period, Instant.parse("2026-10-13T17:59:59Z")).isEmpty());
        assertTrue(ScheduleClock.activeInterval(schedule, period, Instant.parse("2026-10-13T18:00:00Z")).isPresent());
        assertTrue(ScheduleClock.activeInterval(schedule, period, Instant.parse("2026-10-14T02:00:00Z")).isEmpty());
    }

    @Test
    void overnightWindowSplitsAccountingAtActualLocalMidnight() {
        ActivitySchedule schedule = schedule(LocalTime.of(22, 0), LocalTime.of(2, 0));
        Instant before = Instant.parse("2026-10-14T03:59:59Z");
        Instant after = Instant.parse("2026-10-14T04:00:00Z");
        AccountingPeriod firstPeriod = AccountingPeriod.containing(before, NEW_YORK);
        AccountingPeriod secondPeriod = AccountingPeriod.containing(after, NEW_YORK);
        TimeInterval first = ScheduleClock.activeInterval(schedule, firstPeriod, before).orElseThrow();
        TimeInterval second = ScheduleClock.activeInterval(schedule, secondPeriod, after).orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 13), firstPeriod.localDate());
        assertEquals(LocalDate.of(2026, 10, 14), secondPeriod.localDate());
        assertEquals(after, first.endExclusive());
        assertEquals(after, second.startInclusive());
        assertEquals(Instant.parse("2026-10-14T06:00:00Z"), second.endExclusive());
        assertTrue(ScheduleClock.activeInterval(schedule, secondPeriod, second.endExclusive()).isEmpty());
    }

    @Test
    void springGapMovesBoundaryToFirstValidInstantRatherThanPreservingMinutes() {
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), ScheduleClock.resolveBoundary(
                LocalDateTime.of(2026, 3, 8, 2, 30), NEW_YORK, true));
        TimeInterval interval = ScheduleClock.windowOn(LocalDate.of(2026, 3, 8),
                new ActivityWindow(LocalTime.of(2, 30), LocalTime.of(3, 30)), NEW_YORK).orElseThrow();
        assertEquals(Duration.ofMinutes(30), Duration.between(interval.startInclusive(), interval.endExclusive()));
        assertTrue(ScheduleClock.windowOn(LocalDate.of(2026, 3, 8),
                new ActivityWindow(LocalTime.of(2, 15), LocalTime.of(2, 45)), NEW_YORK).isEmpty());
    }

    @Test
    void fallOverlapUsesEarlierStartAndLaterEndWithoutAnotherBucket() {
        TimeInterval interval = ScheduleClock.windowOn(LocalDate.of(2026, 11, 1),
                new ActivityWindow(LocalTime.of(1, 15), LocalTime.of(1, 45)), NEW_YORK).orElseThrow();
        assertEquals(Instant.parse("2026-11-01T05:15:00Z"), interval.startInclusive());
        assertEquals(Instant.parse("2026-11-01T06:45:00Z"), interval.endExclusive());
        AccountingPeriod first = AccountingPeriod.containing(Instant.parse("2026-11-01T05:30:00Z"), NEW_YORK);
        AccountingPeriod repeated = AccountingPeriod.containing(Instant.parse("2026-11-01T06:30:00Z"), NEW_YORK);
        assertEquals(first, repeated);
    }

    @Test
    void actualUtcPeriodsHaveTwentyThreeAndTwentyFiveHoursAcrossDst() {
        AccountingPeriod spring = AccountingPeriod.on(LocalDate.of(2026, 3, 8), NEW_YORK);
        AccountingPeriod fall = AccountingPeriod.on(LocalDate.of(2026, 11, 1), NEW_YORK);
        assertEquals(Duration.ofHours(23), Duration.between(spring.startInclusive(), spring.endExclusive()));
        assertEquals(Duration.ofHours(25), Duration.between(fall.startInclusive(), fall.endExclusive()));
    }

    @Test
    void completedPeriodCannotReopenAfterWallClockRollback() {
        AccountingPeriod completed = AccountingPeriod.on(LocalDate.of(2026, 10, 13), NEW_YORK);
        assertTrue(ScheduleClock.openPeriod(Instant.parse("2026-10-13T18:00:00Z"), NEW_YORK,
                completed.endExclusive()).isEmpty());
        assertEquals(LocalDate.of(2026, 10, 14), ScheduleClock.openPeriod(completed.endExclusive(),
                NEW_YORK, completed.endExclusive()).orElseThrow().localDate());
    }

    @Test
    void timezoneChangeWaitsForANonOverlappingPeriod() {
        AccountingPeriod previous = AccountingPeriod.on(LocalDate.of(2026, 10, 13), NEW_YORK);
        ZoneId losAngeles = ZoneId.of("America/Los_Angeles");
        assertTrue(ScheduleClock.openPeriod(previous.endExclusive(), losAngeles,
                previous.endExclusive()).isEmpty());
        AccountingPeriod next = ScheduleClock.openPeriod(Instant.parse("2026-10-14T07:00:00Z"),
                losAngeles, previous.endExclusive()).orElseThrow();
        assertFalse(next.startInclusive().isBefore(previous.endExclusive()));
        assertThrows(IllegalArgumentException.class, () -> ScheduleClock.activeInterval(
                ActivitySchedule.defaults(losAngeles, Duration.ofHours(4)), previous,
                previous.startInclusive().plusSeconds(1)));
    }

    @Test
    void theFirstPeriodIsTheLocalDayAndItStaysCurrentAfterAZoneEdit() {
        Instant now = Instant.parse("2026-10-13T18:00:00Z");
        PeriodTransition first = ScheduleClock.transition(now, NEW_YORK, Optional.empty());
        assertEquals(PeriodTransition.Kind.OPEN, first.kind());
        assertEquals(AccountingPeriod.on(LocalDate.of(2026, 10, 13), NEW_YORK), first.period().orElseThrow());
        // A zone edit mid-period leaves the saved period current to its end.
        PeriodTransition edited = ScheduleClock.transition(now.plusSeconds(3600), ZoneId.of("Asia/Tokyo"), first.period());
        assertEquals(PeriodTransition.Kind.CURRENT, edited.kind());
        assertEquals(first.period(), edited.period());
    }

    @Test
    void theNextLocalDayOpensAtTheEndAcrossDstAndIdleDaysAreSkipped() {
        for (LocalDate date : List.of(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 13))) {
            AccountingPeriod last = AccountingPeriod.on(date, NEW_YORK);
            PeriodTransition next = ScheduleClock.transition(last.endExclusive(), NEW_YORK, Optional.of(last));
            assertEquals(PeriodTransition.Kind.OPEN, next.kind());
            assertEquals(AccountingPeriod.on(date.plusDays(1), NEW_YORK), next.period().orElseThrow());
            assertEquals(last.endExclusive(), next.period().orElseThrow().startInclusive());
        }
        AccountingPeriod last = AccountingPeriod.on(LocalDate.of(2026, 10, 13), NEW_YORK);
        PeriodTransition later = ScheduleClock.transition(last.endExclusive().plus(Duration.ofDays(3)), NEW_YORK, Optional.of(last));
        assertEquals(LocalDate.of(2026, 10, 17), later.period().orElseThrow().localDate());
    }

    @Test
    void aClockBehindTheLatestPeriodChargesNothingUntilItCatchesUp() {
        AccountingPeriod last = AccountingPeriod.on(LocalDate.of(2026, 10, 14), NEW_YORK);
        PeriodTransition behind = ScheduleClock.transition(last.startInclusive().minusSeconds(1), NEW_YORK, Optional.of(last));
        assertEquals(PeriodTransition.Kind.CLOCK_BEHIND, behind.kind());
        assertFalse(behind.chargeable());
        assertEquals(last.startInclusive(), behind.opensAt().orElseThrow());
        assertEquals(PeriodTransition.Kind.CURRENT,
                ScheduleClock.transition(last.startInclusive(), NEW_YORK, Optional.of(last)).kind());
    }

    @Test
    void aZoneEditWaitsForItsFirstWholeLocalDayWestAndEast() {
        AccountingPeriod utc = AccountingPeriod.on(LocalDate.of(2026, 10, 13), ZoneOffset.UTC);
        // West: New York's 13 October runs to 04:00Z on the 14th, past the UTC period's end.
        PeriodTransition west = ScheduleClock.transition(Instant.parse("2026-10-14T01:00:00Z"), NEW_YORK, Optional.of(utc));
        assertEquals(PeriodTransition.Kind.ZONE_PENDING, west.kind());
        assertEquals(Instant.parse("2026-10-14T04:00:00Z"), west.opensAt().orElseThrow());
        assertEquals(AccountingPeriod.on(LocalDate.of(2026, 10, 14), NEW_YORK), ScheduleClock.transition(
                west.opensAt().orElseThrow(), NEW_YORK, Optional.of(utc)).period().orElseThrow());
        // East: Tokyo's 14 October began at 15:00Z on the 13th, inside the UTC period, so its 15th is the first whole day.
        PeriodTransition east = ScheduleClock.transition(utc.endExclusive(), ZoneId.of("Asia/Tokyo"), Optional.of(utc));
        assertEquals(PeriodTransition.Kind.ZONE_PENDING, east.kind());
        assertEquals(Instant.parse("2026-10-14T15:00:00Z"), east.opensAt().orElseThrow());
    }

    @Test
    void periodsOpenedAcrossZoneEditsNeverOverlapOrReopen() {
        List<ZoneId> zones = List.of(ZoneOffset.UTC, NEW_YORK, ZoneId.of("Asia/Tokyo"), ZoneId.of("Pacific/Kiritimati"),
                ZoneId.of("Pacific/Pago_Pago"), ZoneId.of("Asia/Kathmandu"));
        for (ZoneId from : zones) for (ZoneId to : zones) {
            AccountingPeriod last = AccountingPeriod.on(LocalDate.of(2026, 3, 7), from);
            Instant end = last.endExclusive().plus(Duration.ofDays(4));
            for (Instant now = last.startInclusive(); now.isBefore(end); now = now.plus(Duration.ofMinutes(45))) {
                PeriodTransition next = ScheduleClock.transition(now, to, Optional.of(last));
                if (next.kind() == PeriodTransition.Kind.OPEN) {
                    AccountingPeriod opened = next.period().orElseThrow();
                    assertTrue(opened.contains(now), from + " to " + to + " at " + now);
                    assertFalse(opened.startInclusive().isBefore(last.endExclusive()), from + " to " + to + " at " + now);
                    last = opened;
                } else if (next.kind() == PeriodTransition.Kind.ZONE_PENDING) {
                    assertTrue(next.opensAt().orElseThrow().isAfter(now), from + " to " + to + " at " + now);
                    assertEquals(PeriodTransition.Kind.OPEN,
                            ScheduleClock.transition(next.opensAt().orElseThrow(), to, Optional.of(last)).kind());
                } else {
                    assertEquals(PeriodTransition.Kind.CURRENT, next.kind());
                }
            }
            assertEquals(to, last.zone(), from + " to " + to);
        }
    }

    @Test
    void aTransitionNamesAPeriodOnlyWhenChargeable() {
        AccountingPeriod period = AccountingPeriod.on(LocalDate.of(2026, 10, 13), NEW_YORK);
        assertThrows(IllegalArgumentException.class,
                () -> new PeriodTransition(PeriodTransition.Kind.CURRENT, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new PeriodTransition(PeriodTransition.Kind.ZONE_PENDING,
                Optional.of(period), Optional.of(period.endExclusive())));
        assertThrows(IllegalArgumentException.class, () -> new PeriodTransition(PeriodTransition.Kind.OPEN,
                Optional.of(period), Optional.of(period.endExclusive())));
    }

    private static ActivitySchedule schedule(LocalTime start, LocalTime end) {
        return new ActivitySchedule(NEW_YORK, Optional.of(new ActivityWindow(start, end)),
                Duration.ofHours(2), Duration.ofHours(4), true);
    }
}
