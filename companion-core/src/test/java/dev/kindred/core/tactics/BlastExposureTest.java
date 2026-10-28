package dev.kindred.core.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastExposureTest {
    @Test
    void inTheOpenTheWholeBoxIsReachedAndTheDamageIsVanillas() {
        BlastExposure.Rays open = (a, b, c, d, e, f) -> false;
        assertEquals(1.0, BlastExposure.exposure(0, 0, 0, BlastExposure.Box.player(3, 0, 0), open), 1e-9);
        // A crystal's blast three blocks off in the open: v = 3/12, e = 0.75, (0.5625 + 0.75) / 2 * 7 * 12 + 1.
        assertEquals((float) ((0.75 * 0.75 + 0.75) / 2.0 * 7.0 * 12.0 + 1.0), BlastExposure.damage(6, 3, 1.0), 1e-6);
        assertEquals(0.0, BlastExposure.damage(6, 12.5, 1.0), "nothing beyond twice its power");
    }

    @Test
    void aWallBetweenStopsWhatItCovers() {
        // A wall at x = 1.5 from y 0 to 2 stops every ray crossing it under its top.
        BlastExposure.Rays wall = (fx, fy, fz, tx, ty, tz) -> {
            if ((fx - 1.5) * (tx - 1.5) >= 0) return false;
            double t = (1.5 - fx) / (tx - fx);
            double y = fy + t * (ty - fy);
            return y >= 0 && y <= 2;
        };
        double behind = BlastExposure.exposure(0, 0.5, 0, BlastExposure.Box.player(3, 0, 0), wall);
        assertEquals(0.0, behind, 1e-9, "wholly behind a wall as high as it is");
        // A wall one block high covers the legs only.
        BlastExposure.Rays low = (fx, fy, fz, tx, ty, tz) -> {
            if ((fx - 1.5) * (tx - 1.5) >= 0) return false;
            double t = (1.5 - fx) / (tx - fx);
            double y = fy + t * (ty - fy);
            return y >= 0 && y <= 1;
        };
        double half = BlastExposure.exposure(0, 0.5, 0, BlastExposure.Box.player(3, 0, 0), low);
        // Rays from below a block and a half up cross the wall under its top; only the head's get through.
        assertTrue(half > 0.05 && half < 0.3, "part of it: " + half);
        assertTrue(BlastExposure.toPlayer(6, 0, 0.5, 0, 3, 0, 0, low) < BlastExposure.toPlayer(6, 0, 0.5, 0, 3, 0, 0, (a, b, c, d, e, f) -> false));
    }

    @Test
    void safeWhenWhatIsLeftAfterArmourIsEnough() {
        assertTrue(BlastExposure.safe(4, 20, 8, 20, 6));
        assertFalse(BlastExposure.safe(60, 0, 0, 20, 1));
    }
}
