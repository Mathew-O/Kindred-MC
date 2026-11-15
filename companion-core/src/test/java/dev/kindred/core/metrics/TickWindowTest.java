package dev.kindred.core.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** In plain terms: the tick figures the cost line gives are what the ticks took: the mean, the 99th, the slow ones, the longest. */
class TickWindowTest {
    private static final long MS = 1_000_000L;

    @Test
    void theMeanThe99thTheSlowOnesAndTheLongestAreWhatTheTicksTook() {
        var window = new TickWindow();
        assertEquals("ticks 0", window.describe());
        // 990 ticks of 20 ms, 9 of 60 ms, one of 1,130 ms (at tick 777).
        for (int i = 0; i < 990; i++) window.add(20 * MS, i);
        for (int i = 0; i < 9; i++) window.add(60 * MS, 990 + i);
        window.add(1_130 * MS, 777);
        assertEquals(1_000, window.count());
        assertEquals((990 * 20 + 9 * 60 + 1_130) / 1_000.0, window.meanMs(), 1e-9);
        assertEquals(21, window.p99Ms(), "990 of 1,000 under 21 ms: the 99th is the 20 ms bucket's edge");
        assertEquals(10, window.over50());
        assertEquals(1_130.0, window.longestMs(), 1e-9);
        assertEquals(777, window.longestTick());
        assertEquals("ticks 1000: mean 21.5 ms, p99 21 ms, 10 over 50 ms, longest 1130.0 ms (tick 777)", window.describe());
    }

    @Test
    void aWindowOfLongTicksSaysSoAndAResetBeginsAgain() {
        var window = new TickWindow();
        for (int i = 0; i < 100; i++) window.add(3_000 * MS, i);
        assertEquals(TickWindow.MAX_MS + 1, window.p99Ms());
        assertTrue(window.describe().contains("p99 over 2000 ms"), window.describe());
        window.reset();
        assertEquals(0, window.count());
        assertEquals("ticks 0", window.describe());
        window.add(-1, 5);
        assertEquals(0, window.count(), "a tick with no time read is not counted");
    }
}
