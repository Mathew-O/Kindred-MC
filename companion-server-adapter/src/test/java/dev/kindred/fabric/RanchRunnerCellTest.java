package dev.kindred.fabric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/**
 * F-22c129: the cell a middle is read in against a pen's ring (`RanchRunner.cellOf`). A 7 by 7 pen at the origin, its gate
 * at (3, 6) in the south row, shut and facing south, so its thin axis is z.
 */
final class RanchRunnerCellTest {
    private static int[] gate(double x, double z) {
        return RanchRunner.cellOf(false, true, true, 3, 6, x, z, 0, 0, 7, 7);
    }

    @Test
    void pressedAgainstTheShutGateFromInsideIsInTheCellInside() {
        assertArrayEquals(new int[] {3, 5}, gate(3.5, 6.075));
    }

    @Test
    void pressedFlushJustPastTheFaceDoesNotFlickerIntoTheGatesCell() {
        assertArrayEquals(new int[] {3, 5}, gate(3.5, 6.0755));
        assertArrayEquals(new int[] {3, 7}, gate(3.5, 6.9245));
    }

    @Test
    void pressedAgainstTheShutGateFromOutsideIsInTheCellOutside() {
        assertArrayEquals(new int[] {3, 7}, gate(3.5, 6.925));
    }

    @Test
    void aMiddleWellInsideTheShutGateIsInTheGatesOwnCell() {
        // The staged player, set at the cell's middle, and CI's, 0.53 in (run 45143611807).
        assertArrayEquals(new int[] {3, 6}, gate(3.5, 6.50));
        assertArrayEquals(new int[] {3, 6}, gate(3.5, 6.53));
    }

    @Test
    void aShutGateAcrossXIsReadAcrossX() {
        // A gate in the west row (0, 3), facing west: its thin axis x.
        assertArrayEquals(new int[] {0, 3}, RanchRunner.cellOf(false, true, false, 0, 3, 0.5, 3.5, 0, 0, 7, 7));
        assertArrayEquals(new int[] {-1, 3}, RanchRunner.cellOf(false, true, false, 0, 3, 0.05, 3.5, 0, 0, 7, 7));
        assertArrayEquals(new int[] {1, 3}, RanchRunner.cellOf(false, true, false, 0, 3, 0.95, 3.5, 0, 0, 7, 7));
    }

    @Test
    void aFenceIsAsBeforeTheCellBesideOnItsOwnSide() {
        // A fence's middle is never kept as its own cell, however far in.
        assertArrayEquals(new int[] {2, 5}, RanchRunner.cellOf(true, false, false, 2, 6, 2.5, 6.40, 0, 0, 7, 7));
        assertArrayEquals(new int[] {2, 7}, RanchRunner.cellOf(true, false, false, 2, 6, 2.5, 6.53, 0, 0, 7, 7));
        assertArrayEquals(new int[] {-1, 3}, RanchRunner.cellOf(true, false, false, 0, 3, 0.2, 3.5, 0, 0, 7, 7));
        assertArrayEquals(new int[] {1, 3}, RanchRunner.cellOf(true, false, false, 0, 3, 0.8, 3.5, 0, 0, 7, 7));
    }

    @Test
    void anOpenGateOrAnyOtherBlockIsItsOwnCell() {
        assertArrayEquals(new int[] {3, 6}, RanchRunner.cellOf(false, false, false, 3, 6, 3.5, 6.9, 0, 0, 7, 7));
        assertArrayEquals(new int[] {3, 3}, RanchRunner.cellOf(false, false, false, 3, 3, 3.1, 3.9, 0, 0, 7, 7));
    }
}
