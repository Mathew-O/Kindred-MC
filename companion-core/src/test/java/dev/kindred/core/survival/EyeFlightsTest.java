package dev.kindred.core.survival;

import dev.kindred.core.survival.EyeFlights.Flight;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how a player finds the stronghold by throwing eyes of ender from two places: where
 * the two flights cross is the stronghold, flights too nearly parallel or crossing somewhere impossible are
 * not trusted, and the second throw is made off to one side of the first.
 */
class EyeFlightsTest {
    /** A flight thrown from (x, z) toward a stronghold at (sx, sz), seen with a small error in its heading. */
    private static Flight toward(double x, double z, double sx, double sz, double errorDegrees) {
        double heading = Math.atan2(sz - z, sx - x) + Math.toRadians(errorDegrees);
        return new Flight(x, z, Math.cos(heading), Math.sin(heading));
    }

    @Test
    void twoFlightsFromWellApartCrossAtTheStronghold() {
        // A stronghold 1,600 blocks out; throws 300 blocks apart, square to the first heading.
        var first = toward(0, 0, 1200, 1060, 0);
        double[] side = EyeFlights.secondThrow(first, 300);
        var second = toward(side[0], side[1], 1200, 1060, 0);
        double[] at = EyeFlights.crossing(first, second).orElseThrow();
        assertEquals(1200, at[0], 1e-6);
        assertEquals(1060, at[1], 1e-6);
        // A quarter of a degree off on each throw, in opposite ways: still within five chunks of it. An eye flies
        // straight at its target, so a watched heading is better than that; a whole degree each way would put the
        // estimate some 300 blocks off at this range, which is why the campaign throws again once closer.
        double[] rough = EyeFlights.crossing(toward(0, 0, 1200, 1060, 0.25), toward(side[0], side[1], 1200, 1060, -0.25)).orElseThrow();
        assertTrue(Math.hypot(rough[0] - 1200, rough[1] - 1060) < 80, "within five chunks: " + rough[0] + ", " + rough[1]);
    }

    @Test
    void nearlyParallelFlightsAreNotTrusted() {
        // Thrown three blocks apart along the same line: the headings barely differ.
        var a = toward(0, 0, 1500, 0, 0);
        var b = toward(0, 3, 1500, 0, 0);
        assertTrue(EyeFlights.angle(a, b) < EyeFlights.MIN_ANGLE);
        assertTrue(EyeFlights.crossing(a, b).isEmpty(), "throw again from further to the side");
    }

    @Test
    void aCrossingBehindTheThrowsOrBeyondAnyStrongholdIsAMisreading() {
        // The lines meet behind both throws: the eyes flew away from that point.
        var a = new Flight(0, 0, 1, 1);
        var b = new Flight(100, 0, -1, 1);
        assertTrue(EyeFlights.crossing(a, b).isPresent(), "they meet ahead at (50, 50)");
        var away = new Flight(0, 0, -1, -1);
        var awayToo = new Flight(100, 0, 1, -1);
        assertTrue(EyeFlights.crossing(away, awayToo).isEmpty(), "behind both");
        // Far beyond the outermost ring.
        var c = new Flight(0, 0, 1, 0.0001);
        var d = new Flight(0, 100, 1, -0.0016);
        assertTrue(EyeFlights.angle(c, d) < EyeFlights.MIN_ANGLE || EyeFlights.crossing(c, d).isEmpty());
    }

    @Test
    void theSecondThrowIsSquareToTheFirstHeading() {
        var first = new Flight(10, 20, 0, 5); // Heading south.
        double[] side = EyeFlights.secondThrow(first, 100);
        assertEquals(-90, side[0], 1e-9);
        assertEquals(20, side[1], 1e-9);
        assertEquals(100, first.offLine(side[0], side[1]), 1e-9);
        assertEquals(0, first.yaw(), 1e-9, "south is yaw 0");
        assertThrows(IllegalArgumentException.class, () -> new Flight(0, 0, 0, 0));
    }
}
