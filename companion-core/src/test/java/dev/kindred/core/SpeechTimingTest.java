package dev.kindred.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeechTimingTest {
    @Test
    void longerLinesWaitLongerAndBothEndsAreBounded() {
        long hi = SpeechTiming.delayMillis("Hi");
        long sentence = SpeechTiming.delayMillis("Hello! Nice to have some company.");
        long essay = SpeechTiming.delayMillis("x".repeat(400));
        assertTrue(hi >= SpeechTiming.MIN_MILLIS);
        assertTrue(sentence > hi, "a sentence waits longer than a greeting");
        assertEquals(SpeechTiming.MAX_MILLIS, essay);
        assertTrue(sentence < SpeechTiming.MAX_MILLIS);
        assertEquals(hi, SpeechTiming.delayMillis("  Hi  "));
    }
}
