package dev.kindred.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToneDriftTest {
    private static final long MINUTE = 60_000L;

    @Test
    void toneMovesWithAppliedDeltasDecaysMonotonicallyAndStaysClamped() {
        ToneDrift tone = ToneDrift.neutral(0).afterIncident(-6, 0); // verified unauthorized taking
        assertEquals(-15, tone.current(0));
        double previous = tone.current(0);
        for (long t = MINUTE; t <= 240 * MINUTE; t += 5 * MINUTE) {
            double now = tone.current(t);
            assertTrue(now >= previous, "sour tone eases, never deepens, at " + t);
            previous = now;
        }
        assertEquals(-7.5, tone.current(ToneDrift.NEGATIVE_HALF_LIFE_MILLIS), 1e-9);
        assertEquals(0, tone.current(ToneDrift.HARD_EXPIRY_MILLIS));
        ToneDrift pleased = ToneDrift.neutral(0).afterIncident(3, 0).afterIncident(3, 0).afterIncident(3, 0).afterIncident(3, 0).afterIncident(3, 0);
        assertEquals(ToneDrift.MAX_OFFSET, pleased.current(0));
        assertEquals(ToneDrift.MAX_OFFSET / 2, pleased.current(ToneDrift.POSITIVE_HALF_LIFE_MILLIS), 1e-9);
        assertEquals(0, ToneDrift.neutral(0).afterIncident(-1, 0).current(45 * MINUTE), "below one point reads as silent");
        assertEquals(-2.5 * 2, ToneDrift.neutral(0).afterIncident(-2, 0).current(0));
    }

    @Test
    void deduplicatedRepeatsAddNothingAndRollbackNeitherResoursNorExtends() {
        ToneDrift tone = ToneDrift.neutral(0).afterIncident(-6, 0);
        assertEquals(tone.current(10 * MINUTE), tone.afterIncident(0, 10 * MINUTE).current(10 * MINUTE));
        double later = tone.current(60 * MINUTE);
        ToneDrift observed = tone.observe(60 * MINUTE);
        assertEquals(later, observed.current(5 * MINUTE), 1e-9, "a rolled-back clock reads as no time passing");
        assertEquals(later, observed.afterIncident(0, 5 * MINUTE).current(5 * MINUTE), 1e-9);
        ToneDrift restarted = new ToneDrift(observed.offset(), observed.at(), observed.latestObserved());
        assertEquals(later, restarted.current(60 * MINUTE), 1e-9, "restart preserves offset and timestamps");
        assertThrows(IllegalArgumentException.class, () -> new ToneDrift(31, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ToneDrift(0, 10, 5));
    }

    @Test
    void relationshipBandComesOnlyFromTheDurableScoreAndToneStaysInsideIt() {
        int distant = 10;
        int close = 90;
        assertEquals(ToneDrift.Band.DISTANT, ToneDrift.Band.of(distant));
        assertEquals(ToneDrift.Band.CLOSE, ToneDrift.Band.of(close));
        // A distant peer who just did something kind sounds briefly less curt, but is still distant.
        int distantWarmed = ToneDrift.effectiveWarmth(distant, 30);
        assertEquals(ToneDrift.Band.DISTANT.maxWarmth, distantWarmed);
        assertEquals(ToneDrift.Mood.COOL, ToneDrift.mood(distantWarmed));
        // A close friend who just annoyed the companion sounds cooler but never like a stranger.
        int closeAnnoyed = ToneDrift.effectiveWarmth(close, -30);
        assertEquals(ToneDrift.Band.CLOSE.minWarmth, closeAnnoyed);
        assertEquals(ToneDrift.Mood.WARM, ToneDrift.mood(closeAnnoyed));
        // With no tone, the band's baseline alone decides: steady, not hostile, not gushing.
        assertEquals(ToneDrift.Mood.CURT, ToneDrift.mood(ToneDrift.effectiveWarmth(distant, 0)));
        assertEquals(ToneDrift.Mood.ORDINARY, ToneDrift.mood(ToneDrift.effectiveWarmth(50, 0)));
        assertEquals(ToneDrift.Mood.PLEASED, ToneDrift.mood(ToneDrift.effectiveWarmth(close, 0)));
        assertEquals(ToneDrift.Mood.COOL, ToneDrift.mood(ToneDrift.effectiveWarmth(50, -8)));
        assertThrows(IllegalArgumentException.class, () -> ToneDrift.Band.of(101));
    }

    @Test
    void readingToneHasNoSideEffectsAndCorrectionsClearIt() {
        ToneDrift tone = ToneDrift.neutral(0).afterIncident(-3, 0);
        int friendshipBefore = 47; // the durable record lives elsewhere; this policy never touches it
        tone.current(10 * MINUTE);
        tone.millisUntilSilent(10 * MINUTE);
        assertEquals(47, friendshipBefore);
        assertEquals(tone, ToneDrift.neutral(0).afterIncident(-3, 0));
        ToneDrift cleared = tone.cleared(20 * MINUTE);
        assertEquals(0, cleared.current(20 * MINUTE));
        assertEquals(0, cleared.millisUntilSilent(20 * MINUTE));
        long untilSilent = tone.millisUntilSilent(0);
        assertTrue(untilSilent > 0 && untilSilent <= ToneDrift.HARD_EXPIRY_MILLIS);
        assertEquals(0, tone.current(untilSilent + 1));
        assertTrue(Math.abs(tone.current(untilSilent - MINUTE)) >= ToneDrift.SILENT_BELOW);
    }
}
