package dev.kindred.core.route;

/**
 * In plain terms: one block's place in the world, by its whole-number x, y and z, as the route planners see it.
 *
 * Technical: An immutable integer block position for the planners in this package. {@link #asLong()} packs it as the
 * game packs its own block positions (26 bits of x, 26 of z, 12 of y), so a cell in the world has one key and no two
 * cells share one.
 */
public record Cell(int x, int y, int z) {
    private static final int XZ_BITS = 26, Y_BITS = 12;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1, Y_MASK = (1L << Y_BITS) - 1;

    /** The cell one up. */
    public Cell up() { return new Cell(x, y + 1, z); }

    /** The cell {@code n} up. */
    public Cell up(int n) { return n == 0 ? this : new Cell(x, y + n, z); }

    /** The cell one down. */
    public Cell down() { return new Cell(x, y - 1, z); }

    /** The cell {@code n} down. */
    public Cell down(int n) { return n == 0 ? this : new Cell(x, y - n, z); }

    /** The cell this far off along each axis. */
    public Cell add(int dx, int dy, int dz) { return dx == 0 && dy == 0 && dz == 0 ? this : new Cell(x + dx, y + dy, z + dz); }

    /** The cell next to this one on {@code side}. */
    public Cell offset(Side side) { return new Cell(x + side.dx, y + side.dy, z + side.dz); }

    /** The cell's key: one long for each cell of the world. */
    public long asLong() {
        return (x & XZ_MASK) << (XZ_BITS + Y_BITS) | (z & XZ_MASK) << Y_BITS | (y & Y_MASK);
    }

    /** The squared distance between this cell and another, as whole blocks along each axis. */
    public double squaredDistance(Cell other) {
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
