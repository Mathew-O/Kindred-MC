package dev.kindred.core.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks which way a chased player runs: straight away from whoever chases it, but never
 * toward its own home while any other way is open.
 */
class EvasionCourseTest {
    @Test
    void withNoHomeToKeepFromItRunsStraightAwayFirst() {
        assertEquals(List.of(0.0, 45.0, -45.0, 90.0, -90.0, 135.0, -135.0), EvasionCourse.turns(0, -1, 0, 0));
    }

    @Test
    void itNeverRunsTowardItsHomeWhileAnyOtherWayIsOpen() {
        // Chased from the south toward home in the north: straight away and a little either side are toward home.
        var turns = EvasionCourse.turns(0, -1, 0, -1);
        assertEquals(List.of(90.0, -90.0, 135.0, -135.0), turns);
        assertTrue(EvasionCourse.towardHome(0, -1, 0, -1, 0));
        assertTrue(EvasionCourse.towardHome(0, -1, 0, -1, 45));
        assertFalse(EvasionCourse.towardHome(0, -1, 0, -1, 90));
        // Home off to the north-east: straight away north is within sixty degrees of it, so is turning toward it.
        var oblique = EvasionCourse.turns(0, -1, 1, -1);
        assertFalse(oblique.contains(0.0));
        assertTrue(oblique.getFirst() != 0.0 && oblique.size() >= 3, "Ways off away from home: " + oblique);
        // Home behind it, past the pursuer: running away is running from home too, as it would.
        assertEquals(0.0, EvasionCourse.turns(0, -1, 0, 1).getFirst());
    }

    /** A turn to the left or right means the same here as it does in the game. */
    @Test
    void theTurnsMatchTheAdaptersWayOfTurning() {
        // A quarter turn of north (0, -1) as Minecraft turns it: x' = x cos + z sin = -1, z' = z cos - x sin = 0, west.
        // So with home to the west, the quarter turn that way is toward home and the other is not.
        assertTrue(EvasionCourse.towardHome(0, -1, -1, 0, 90));
        assertFalse(EvasionCourse.towardHome(0, -1, -1, 0, -90));
    }
}
