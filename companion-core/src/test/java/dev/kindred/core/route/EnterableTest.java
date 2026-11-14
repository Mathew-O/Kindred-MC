package dev.kindred.core.route;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-22c74: a goal no move ends in. A player two down in its pit, a stair cut up out of it, a pocket one high mined beside
 * its feet and the cell under the pocket mined too: that lower cell is a stand, but no step, drop or jump gets into it
 * while stone lies over the pocket. The search is told so before it spends its positions.
 */
class EnterableTest {
    private static Cell at(int x, int y, int z) { return new Cell(x, y, z); }

    /** Stone to y 1 over the box's middle, air above; the pit, its stair, the pocket and the cell under it mined. */
    private static RoutePlannerTest.Box pit(boolean headRoom) {
        var box = new RoutePlannerTest.Box();
        for (int x = -7; x <= 12; x++) for (int z = -7; z <= 7; z++) for (int y = -6; y <= 1; y++) box.set(x, y, z, RoutePlannerTest.Block.STONE);
        for (Cell c : new Cell[] {at(0, 0, 0), at(0, 1, 0), at(-1, 1, 0), at(1, 0, 0), at(1, -1, 0)}) box.set(c.x(), c.y(), c.z(), RoutePlannerTest.Block.AIR);
        if (headRoom) box.set(1, 1, 0, RoutePlannerTest.Block.AIR);
        return box;
    }

    @Test
    void aCellUnderAPocketOneHighIsNoGoalAnyMoveEndsIn() {
        Cell drop = at(1, -1, 0);
        var navigator = new Navigator(pit(false));
        assertTrue(navigator.standable(drop), "a stand: stone under it, two high");
        assertFalse(navigator.enterable(drop, drop::equals));
        assertTrue(new Navigator(pit(true)).enterable(drop, drop::equals), "with the head room over the pocket open, a step down gets into it");
    }

    @Test
    void theSearchForItIsShortAndTheSameWithHeadRoomFindsItAtOnce() {
        Cell drop = at(1, -1, 0);
        var refused = new Navigator(pit(false)).find(at(0, 0, 0), drop, drop::equals, Navigator.DEFAULT_MAX_NODES, 96);
        assertFalse(refused.complete());
        assertTrue(refused.expanded() <= Navigator.UNENTERABLE_NODES, "expanded " + refused.expanded());
        var found = new Navigator(pit(true)).find(at(0, 0, 0), drop, drop::equals, Navigator.DEFAULT_MAX_NODES, 96);
        assertTrue(found.complete());
        assertEquals(drop, found.nodes().get(found.nodes().size() - 1));
    }

    @Test
    void aWiderGoalIsNotJudged() {
        // Any cell within four of the drop: its box's edge is in the goal, so it is taken as enterable.
        Cell drop = at(1, -1, 0);
        assertTrue(new Navigator(pit(false)).enterable(drop, c -> Math.abs(c.x() - 1) <= 4 && Math.abs(c.y() + 1) <= 4 && Math.abs(c.z()) <= 4));
    }
}
