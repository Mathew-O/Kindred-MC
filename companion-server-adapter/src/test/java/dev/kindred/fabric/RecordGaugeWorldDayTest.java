package dev.kindred.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * In plain terms: the daily cost line is numbered by the world's own day, so a server started again goes on with the
 * world's count instead of starting from day 1.
 *
 * Technical: M-06i4 found the line numbered by this start's server ticks: after a restart at world time 61,286 it read
 * "day 1" again, and the pre-flight report, which keys growth on the day, merged different days. {@link
 * RecordGauge#worldDay} is the world's game time in whole days, from 1.
 */
class RecordGaugeWorldDayTest {
    @Test
    void theWorldsDayFromItsGameTime() {
        assertEquals(1, RecordGauge.worldDay(0));
        assertEquals(1, RecordGauge.worldDay(23_999));
        assertEquals(2, RecordGauge.worldDay(24_000));
        assertEquals(3, RecordGauge.worldDay(48_000));
        assertEquals(91, RecordGauge.worldDay(2_160_000), "a month at 72 game days a real day: day 91 on the 30th");
        assertEquals(1, RecordGauge.worldDay(-5), "a time below 0 is day 1, never 0");
    }

    @Test
    void aWorldStartedAgainPastItsSecondDayGoesOnFromItsThird() {
        // M-06i4's restart: the first line after it, at world time 61,286, was "day 1"; it is the world's third day.
        assertEquals(3, RecordGauge.worldDay(61_286));
        // That start's periodic line 24,000 ticks on, and the stop's line after another day, as the rehearsal had them.
        assertEquals(4, RecordGauge.worldDay(61_286 + 24_000));
        assertEquals(4, RecordGauge.worldDay(85_608));
    }
}
