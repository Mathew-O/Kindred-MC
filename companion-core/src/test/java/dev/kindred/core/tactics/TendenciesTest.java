package dev.kindred.core.tactics;

import dev.kindred.core.tactics.Tendencies.Habit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TendenciesTest {
    @Test
    void aTendencyIsCountedOverItsChancesWithAConfidenceThatGrows() {
        var t = new Tendencies();
        assertEquals(0.5, t.rate(Habit.BLOCKS));
        assertEquals(0, t.confidence(Habit.BLOCKS));
        assertEquals(0.5, t.likely(Habit.BLOCKS), "nothing seen, nothing believed");
        // Two blocks in two chances: a high rate, but little believed.
        t.chance(Habit.BLOCKS, true);
        t.chance(Habit.BLOCKS, true);
        assertTrue(t.rate(Habit.BLOCKS) > 0.7);
        assertTrue(t.likely(Habit.BLOCKS) < 0.62, "two chances move it little: " + t.likely(Habit.BLOCKS));
        // Twenty more: believed.
        for (int i = 0; i < 20; i++) t.chance(Habit.BLOCKS, true);
        assertTrue(t.confidence(Habit.BLOCKS) > 0.6);
        assertTrue(t.likely(Habit.BLOCKS) > 0.75, "and its rate weighs: " + t.likely(Habit.BLOCKS));
        // Habits apart: what it does with its shield says nothing of its swing.
        assertEquals(0.5, t.rate(Habit.SPAMS));
    }

    @Test
    void itFollowsAnOpponentThatChangesAsOldChancesFade() {
        var t = new Tendencies();
        for (int i = 0; i < 30; i++) t.chance(Habit.BLOCKS, true);
        double before = t.rate(Habit.BLOCKS);
        for (int i = 0; i < 24; i++) t.chance(Habit.BLOCKS, false);
        assertTrue(before > 0.9 && t.rate(Habit.BLOCKS) < 0.4, "it stopped blocking, and that is believed: " + before + " to " + t.rate(Habit.BLOCKS));
    }

    @Test
    void whereItWillBeIsPredictedOnlyAShortWayAhead() {
        assertArrayEquals(new double[]{0.6, -0.3}, Tendencies.predict(0, 0, 0.2, -0.1, 3), 1e-9);
        // No further than its horizon, however far it is asked.
        assertArrayEquals(Tendencies.predict(0, 0, 0.2, 0, Tendencies.HORIZON), Tendencies.predict(0, 0, 0.2, 0, 100), 1e-12);
        assertEquals(0.2 * Tendencies.HORIZON, Tendencies.predict(0, 0, 0.2, 0, 100)[0], 1e-9);
        assertArrayEquals(new double[]{5, 7}, Tendencies.predict(5, 7, 1, 1, 0), 1e-12);
        assertArrayEquals(new double[]{5, 7}, Tendencies.predict(5, 7, 1, 1, -4), 1e-12);
    }
}
