package dev.kindred.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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

    private static ActivitySchedule schedule(LocalTime start, LocalTime end) {
        return new ActivitySchedule(NEW_YORK, Optional.of(new ActivityWindow(start, end)),
                Duration.ofHours(2), Duration.ofHours(4), true);
    }
}
