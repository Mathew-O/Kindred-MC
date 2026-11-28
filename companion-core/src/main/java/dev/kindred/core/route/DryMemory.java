package dev.kindred.core.route;

import java.util.Objects;
import java.util.Optional;

/**
 * Where a player last stood on dry ground, on its own feet, in which dimension: what it swims back to when it is far out
 * in water with no dry ground in reach where it is (M-06m3). Its own memory only, the cell it stood on; nothing is read of
 * what is there now. A swim back is offered only in the dimension the cell was in, within {@link #BOUND} blocks, and not
 * once given up: after a swim there failed, or one that set out no nearer than the last. Forgotten at a death and a
 * respawn; another dimension's cell is no way back.
 */
public final class DryMemory {
    /** How far back it swims to the dry ground it last stood on, in blocks. */
    public static final int BOUND = 128;
    /** Nearer than this, in blocks, it is there already: no swim back. */
    static final int THERE = 3;

    private String dimension;
    private Cell cell;
    private Cell givenUp;
    private long backFrom = -1;

    /** It stands dry at {@code at} in {@code dimension}: remembered, and anything given up of the cell before it with it. */
    public void stoodDry(String dimension, Cell at) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(at, "at");
        if (at.equals(cell) && dimension.equals(this.dimension)) return;
        this.dimension = dimension;
        this.cell = at;
        this.givenUp = null;
        this.backFrom = -1;
    }

    /** It is in {@code dimension} now: a cell remembered in another is no way back, and is forgotten. */
    public void in(String dimension) {
        if (this.dimension != null && !this.dimension.equals(dimension)) forget();
    }

    /** At a death or a respawn: nothing remembered. */
    public void forget() {
        dimension = null;
        cell = null;
        givenUp = null;
        backFrom = -1;
    }

    /**
     * The cell to swim back to from {@code feet} in {@code dimension}, or empty: none remembered, another dimension's, given
     * up, beyond {@link #BOUND}, or within {@link #THERE} already. A swim back offered counts as set out from here; the next
     * one must set out nearer, or the cell is given up, so that it cannot loop.
     */
    public Optional<Cell> swimBack(String dimension, Cell feet) {
        if (cell == null || !Objects.equals(this.dimension, dimension) || cell.equals(givenUp)) return Optional.empty();
        long far = squared(cell, feet);
        if (far > (long) BOUND * BOUND || far < (long) THERE * THERE) return Optional.empty();
        if (backFrom >= 0 && far >= backFrom) {
            givenUp = cell;
            return Optional.empty();
        }
        backFrom = far;
        return Optional.of(cell);
    }

    /** A swim back that did not get there: that cell given up until it stands dry again. */
    public void swimFailed() {
        givenUp = cell;
    }

    /** The cell remembered, if any (for logs and fixtures). */
    public Optional<Cell> cell() {
        return Optional.ofNullable(cell);
    }

    private static long squared(Cell a, Cell b) {
        long dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
