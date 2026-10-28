package dev.kindred.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the rhythm of sprint-jumping.
 */
class SprintStrideTest {
    private final SprintStride stride = new SprintStride();

    @Test
    void aHopWaitsForAFootfallAndDoesNotRepeatUntilTheGapPasses() {
        assertFalse(stride.tick(true, true, true, 0), "the landing itself is not a hop");
        assertFalse(stride.tick(true, true, true, 1));
        assertTrue(stride.tick(true, true, true, 2));
        for (int age = 3; age <= 8; age++) assertFalse(stride.tick(true, false, true, age), "airborne");
        assertFalse(stride.tick(true, true, true, 9));
        assertFalse(stride.tick(true, true, true, 10));
        assertFalse(stride.tick(true, true, true, 11), "three steps, but the gap has not passed");
        assertFalse(stride.tick(true, true, true, 12));
        assertFalse(stride.tick(true, true, true, 13));
        assertTrue(stride.tick(true, true, true, 14), "one hop after the landing, the step, and the gap");
    }

    @Test
    void shufflingTurningAndWalkingNeverHop() {
        for (int age = 0; age < 30; age++) {
            assertFalse(new SprintStride().tick(true, true, false, age), "not striding");
            assertFalse(new SprintStride().tick(false, true, true, age), "habit or ceiling forbids the jump");
            assertFalse(new SprintStride().tick(true, false, true, age), "in the air");
        }
    }
}
