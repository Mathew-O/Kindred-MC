package dev.kindred.core;

import org.junit.jupiter.api.Test;

import static dev.kindred.core.FollowSpacing.Motion.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how close a follower stays and when it steps aside.
 */
class FollowSpacingTest {
    @Test
    void smallOwnerMovementsDoNotKeepRestartingNavigation() {
        var state = HOLD;
        for (double distance : new double[]{2.6, 2.9, 3.2, 3.0, 3.7}) {
            state = FollowSpacing.next(state, distance * distance);
            assertEquals(HOLD, state);
        }
        state = FollowSpacing.next(state, 4 * 4);
        assertEquals(APPROACH, state);
        assertEquals(APPROACH, FollowSpacing.next(state, 3 * 3));
        assertEquals(HOLD, FollowSpacing.next(state, 2.7 * 2.7));
    }

    @Test
    void crowdingCausesOneBoundedYieldRatherThanOscillation() {
        assertEquals(YIELD, FollowSpacing.next(HOLD, 1));
        assertEquals(YIELD, FollowSpacing.next(APPROACH, 1));
        assertEquals(YIELD, FollowSpacing.next(YIELD, 1.8 * 1.8));
        assertEquals(HOLD, FollowSpacing.next(YIELD, 2.1 * 2.1));
        assertEquals(HOLD, FollowSpacing.next(HOLD, 1.8 * 1.8));
    }

    @Test
    void shoulderTargetsStayBehindAndOutsideTheCrowdingRadius() {
        for (int degrees = -180; degrees <= 180; degrees += 15) {
            double heading = Math.toRadians(degrees);
            for (int side : new int[]{-1, 0, 1}) {
                var offset = FollowSpacing.offset(heading, side);
                assertEquals(-2.25, offset.x() * -Math.sin(heading)
                        + offset.z() * Math.cos(heading), 0.00001);
                double distance = Math.hypot(offset.x(), offset.z());
                assertTrue(distance >= 2 && distance < 2.75,
                        "A reached formation target must permit the movement policy to settle");
            }
        }
    }

    @Test
    void invalidObservationsNeverProduceMovementDecisions() {
        for (double distance : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> FollowSpacing.next(HOLD, distance));
        }
        assertThrows(IllegalArgumentException.class, () -> FollowSpacing.offset(Double.NaN, 1));
    }
}
