package dev.kindred.core.route;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-25c6h: a stand clear of lava and of deep drops within a blow's reach across its level. In a one-wide tunnel with lava
 * in its floor, a back-off that ended two cells from the lava ({@link Navigator#safeStand} looks only at the four cells
 * beside a stand) was knocked into it by a zombie's blow from there.
 */
class ClearOfEdgesTest {
    private static Cell at(int x, int y, int z) { return new Cell(x, y, z); }

    /** Stone to y 0, air above; a tunnel's floor at y 0, the feet at y 1; lava in the floor at x = lavaX unless it is null. */
    private static RoutePlannerTest.Box floor(Integer lavaX, boolean pit) {
        var box = new RoutePlannerTest.Box();
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) for (int y = -8; y <= 0; y++) box.set(x, y, z, RoutePlannerTest.Block.STONE);
        if (lavaX != null) box.set(lavaX, 0, 0, RoutePlannerTest.Block.LAVA);
        // A pit five deep two cells east, deeper than MAX_SAFE_DROP.
        if (pit) for (int y = -5; y <= 0; y++) box.set(2, y, 0, RoutePlannerTest.Block.AIR);
        return box;
    }

    @Test
    void lavaTwoCellsOffIsWithinAZombiesBlowThoughSafeStandPassesIt() {
        var navigator = new Navigator(floor(-2, false));
        Cell feet = at(0, 1, 0);
        assertTrue(navigator.safeStand(feet), "the four cells beside it are lava-free");
        assertFalse(navigator.clearOfEdges(feet, 1.6), "lava two cells off is within a blow of 1.6");
    }

    @Test
    void lavaThreeCellsOffIsClearOfAZombiesBlow() {
        assertTrue(new Navigator(floor(-3, false)).clearOfEdges(at(0, 1, 0), 1.6));
    }

    @Test
    void aDropDeeperThanSafeWithinReachIsNotClear() {
        var navigator = new Navigator(floor(null, true));
        assertFalse(navigator.clearOfEdges(at(0, 1, 0), 1.6), "a five-deep pit two cells off");
        assertTrue(navigator.clearOfEdges(at(-2, 1, 0), 1.6), "four cells from it");
    }

    @Test
    void noReachIsTheStandAlone() {
        assertTrue(new Navigator(floor(-1, false)).clearOfEdges(at(0, 1, 0), 0), "a reach of 0 looks at no cell beside it");
    }
}
