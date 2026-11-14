package dev.kindred.core.route;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-35m1: the columns a player has seen, kept as it saw them. Only a top a sight line reached is kept; one seen changed
 * replaces the old; the farthest from its last look go first when full; the sweep spirals out from where it stands.
 */
class SeenColumnsTest {
    private static final double[] EYE = {0.5, 1.62, 0.5};

    private static Sight.SeenColumns.Top describe(Cell top) {
        return new Sight.SeenColumns.Top(top.x(), top.y(), top.z(), true, 24, false);
    }

    @Test
    void theSpiralVisitsEveryCellOfItsSquareOnceNearestFirst() {
        int radius = 4, side = 2 * radius + 1;
        var seen = new HashSet<Long>();
        int lastRing = 0;
        for (int n = 0; n < side * side; n++) {
            int[] at = Sight.SeenColumns.spiral(n);
            int ring = Math.max(Math.abs(at[0]), Math.abs(at[1]));
            assertTrue(ring <= radius, "inside the square: " + at[0] + "," + at[1]);
            assertTrue(ring >= lastRing, "ring after ring, never back in");
            lastRing = ring;
            assertTrue(seen.add(((long) at[0] << 32) ^ (at[1] & 0xffffffffL)), "once: " + at[0] + "," + at[1]);
        }
        assertEquals(side * side, seen.size());
    }

    @Test
    void onlyTopsItSeesAreKeptAndAWallHidesWhatIsBehindIt() {
        var scene = new SightTest.Scene();
        for (int y = 0; y <= 4; y++) for (int z = -20; z <= 20; z++) scene.set(6, y, z, SightTest.Block.STONE);
        var columns = new Sight.SeenColumns(4096);
        int cast = 0;
        for (int i = 0; i < 200; i++) cast += columns.sweep(scene, EYE, 0, 0, 12, 16, scene::motionTop, SeenColumnsTest::describe);
        assertTrue(cast > 0);
        assertEquals(Optional.of(new Sight.SeenColumns.Top(-5, -1, 0, true, 24, false)), columns.get(-5, 0), "open ground in front of it");
        assertEquals(Optional.of(new Sight.SeenColumns.Top(6, 4, 0, true, 24, false)), columns.get(6, 0), "the wall's own top");
        assertEquals(Optional.empty(), columns.get(10, 0), "the ground behind the wall: never kept");
    }

    @Test
    void aTopSeenChangedReplacesTheOldAndTheVersionGrowsOnlyWithAChange() {
        var columns = new Sight.SeenColumns(16);
        columns.put(new Sight.SeenColumns.Top(1, 4, 1, true, 24, false));
        long v = columns.version();
        columns.put(new Sight.SeenColumns.Top(1, 4, 1, true, 24, false));
        assertEquals(v, columns.version(), "the same top again: no change");
        columns.put(new Sight.SeenColumns.Top(1, 6, 1, true, 24, false));
        assertNotEquals(v, columns.version(), "seen changed: the version grows");
        assertEquals(6, columns.get(1, 1).orElseThrow().y(), "the new top replaces the old");
        assertEquals(1, columns.size());
    }

    @Test
    void whenFullTheFarthestFromItsLastLookGoFirst() {
        var scene = new SightTest.Scene();
        var columns = new Sight.SeenColumns(10);
        // Its last look from (0, 0): the sweep sets where it last looked.
        columns.sweep(scene, EYE, 0, 0, 0, 1, scene::motionTop, SeenColumnsTest::describe);
        for (int x = 1; x <= 10; x++) columns.put(new Sight.SeenColumns.Top(x, 0, 0, true, 24, false));
        assertTrue(columns.size() <= 10, "bounded: " + columns.size());
        assertFalse(columns.get(10, 0).isPresent(), "the farthest went first");
        assertTrue(columns.get(0, 0).isPresent() && columns.get(1, 0).isPresent(), "the nearest stay");
    }
}
