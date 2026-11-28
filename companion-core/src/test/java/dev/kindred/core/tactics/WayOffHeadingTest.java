package dev.kindred.core.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks which way a player gets away when straight away is blocked: straight away when it can walk it,
 * else the nearest way it can walk that still takes it off, none when nothing does, and no swinging between two.
 */
class WayOffHeadingTest {
    private static final int EAST = 0, SOUTH = 4, WEST = 8, NORTH = 12; // +x, +z, -x, -z

    @Test
    void straightAwayIsTakenWhenItCanBeWalked() {
        assertEquals(WEST, WayOffHeading.choose(-1, 0, i -> true, -1).getAsInt());
    }

    @Test
    void withTheRockStraightBehindItTakesTheTunnelThatStillGains() {
        // The staged tunnel: the zombie a little east of south, so straight away is north and a touch west; only the tunnel
        // west and east can be walked, and only west gains.
        int got = WayOffHeading.choose(-0.12, -1, i -> i == WEST || i == EAST, -1).getAsInt();
        assertEquals(WEST, got);
    }

    @Test
    void aWayThatDoesNotGainIsNeverTaken() {
        // Straight away is south; only north can be walked.
        assertTrue(WayOffHeading.choose(0, 1, i -> i == NORTH, -1).isEmpty());
        // Square across straight away gains nothing either.
        assertTrue(WayOffHeading.choose(0, 1, i -> i == EAST || i == WEST, -1).isEmpty());
    }

    @Test
    void ofTwoWalkableWaysTheOneNearestStraightAwayIsTaken() {
        int got = WayOffHeading.choose(0, 1, i -> i == 2 || i == 7, -1).getAsInt(); // 45 and 157.5 degrees from +x; south is 90
        assertEquals(2, got);
    }

    @Test
    void aWayOnceTakenIsKeptWhileItStillGainsAndCanBeWalked() {
        // Straight away is south, blocked; 3 and 5 sit either side of it, equally good. Taken 5 last time, it keeps 5
        // though 3 came first in the search, and as the threat moves a little it does not swing to 3.
        assertEquals(5, WayOffHeading.choose(0, 1, i -> i == 3 || i == 5, 5).getAsInt());
        assertEquals(5, WayOffHeading.choose(0.1, 1, i -> i == 3 || i == 5, 5).getAsInt());
        // Once it no longer gains, or can no longer be walked, the choice is made afresh.
        assertEquals(3, WayOffHeading.choose(0, 1, i -> i == 3, 5).getAsInt());
        assertEquals(SOUTH, WayOffHeading.choose(0, 1, i -> true, NORTH).getAsInt());
    }

    @Test
    void theHeadingNearestADirection() {
        assertEquals(EAST, WayOffHeading.nearest(1, 0));
        assertEquals(SOUTH, WayOffHeading.nearest(0, 1));
        assertEquals(WEST, WayOffHeading.nearest(-1, 0.01));
        assertEquals(NORTH, WayOffHeading.nearest(0, -1));
    }
}
