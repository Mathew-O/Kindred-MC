package dev.kindred.scheduler;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch02ScheduleTest {

    private final ZoneId zone=ZoneId.of("America/New_York");
    @Test void dstDaysHaveTwentyThreeAndTwentyFiveHours() {
        var spring=AccountingPeriod.containing(Instant.parse("2026-03-08T12:00:00Z"),zone);
        var fall=AccountingPeriod.containing(Instant.parse("2026-11-01T12:00:00Z"),zone);
        assertEquals(Duration.ofHours(23),Duration.between(spring.startInclusive(),spring.endExclusive()));
        assertEquals(Duration.ofHours(25),Duration.between(fall.startInclusive(),fall.endExclusive()));
    }
    @Test void gapCollapsedWindowDoesNotGrantTime() {
        assertTrue(ScheduleClock.windowOn(LocalDate.of(2026,3,8),new ActivityWindow(LocalTime.of(2,10),LocalTime.of(2,50)),zone).isEmpty());
    }
    @Test void overlapUsesEarlierStartAndLaterEnd() {
        var interval=ScheduleClock.windowOn(LocalDate.of(2026,11,1),new ActivityWindow(LocalTime.of(1,15),LocalTime.of(1,45)),zone).orElseThrow();
        assertEquals(Instant.parse("2026-11-01T05:15:00Z"),interval.startInclusive());
        assertEquals(Duration.ofMinutes(90),Duration.between(interval.startInclusive(),interval.endExclusive()));
    }
    @Test void rollbackCannotReopenClosedAccountingDay() {
        Instant now=Instant.parse("2026-10-24T12:00:00Z");var old=AccountingPeriod.containing(now,zone);
        assertTrue(ScheduleClock.openPeriod(now.minusSeconds(3600),zone,old.endExclusive()).isEmpty());
        assertTrue(ScheduleClock.openPeriod(old.endExclusive(),zone,old.endExclusive()).isPresent());
    }

}
