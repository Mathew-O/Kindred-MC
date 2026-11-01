package dev.kindred.core.tactics;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks when the player expects the dragon to reach it: one flying or diving straight at it
 * is timed to the moment its wings reach, while one flying away, passing wide or still far off is no threat.
 */
class ApproachTest {
    private static final double[] TOWER = {0, 80, 0};

    @Test
    void aDragonFlyingStraightAtThePlayerArrivesWhenItsWingsReach() {
        // Thirty-one blocks off, a block a tick: twenty ticks until the wings' reach of eleven.
        assertEquals(OptionalInt.of(20), Approach.within(TOWER, new double[] {31, 80, 0}, new double[] {-1, 0, 0}, Approach.WING_REACH, 60));
        assertEquals(OptionalInt.of(0), Approach.within(TOWER, new double[] {8, 82, 0}, new double[] {0, 0, 0}, Approach.WING_REACH, 60),
                "already within reach, moving or not");
    }

    @Test
    void oneFlyingAwayPassingWideOrTooFarOffIsNoThreat() {
        assertTrue(Approach.within(TOWER, new double[] {31, 80, 0}, new double[] {1, 0, 0}, Approach.WING_REACH, 60).isEmpty(), "away");
        assertTrue(Approach.within(TOWER, new double[] {31, 80, 20}, new double[] {-1, 0, 0}, Approach.WING_REACH, 60).isEmpty(),
                "passing twenty blocks to the side");
        assertTrue(Approach.within(TOWER, new double[] {131, 80, 0}, new double[] {-1, 0, 0}, Approach.WING_REACH, 60).isEmpty(),
                "two seconds away is beyond the horizon");
        assertTrue(Approach.within(TOWER, new double[] {31, 80, 0}, new double[] {0, 0, 0}, Approach.WING_REACH, 60).isEmpty(), "hovering far off");
    }

    @Test
    void aDiveFromAboveCountsTheHeightToo() {
        // Fifteen above and twenty out, diving along the line to the player at 1.25 a tick: the line is 25 long, so
        // the wings reach after (25 - 11) / 1.25 = 11.2 ticks.
        assertEquals(OptionalInt.of(12), Approach.within(TOWER, new double[] {20, 95, 0}, new double[] {-1, -0.75, 0}, Approach.WING_REACH, 60));
    }
}
