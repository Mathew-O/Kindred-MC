package dev.kindred.core.route;

import java.util.List;

/**
 * In plain terms: the six sides of a block: down, up, and the four ways round.
 *
 * Technical: The six faces of a cell, declared in the game's own order (down, up, north, south, west, east), so that
 * {@code values()} visits them as the game does. {@link #HORIZONTAL} is the game's order round the compass (north,
 * east, south, west). The planners' searches break ties by the order they try moves in, so both orders matter.
 */
public enum Side {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

    /** The four sides round the compass, in the game's order. */
    public static final List<Side> HORIZONTAL = List.of(NORTH, EAST, SOUTH, WEST);

    final int dx, dy, dz;

    Side(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    /** Whether the side is one of the four round the compass. */
    public boolean horizontal() { return dy == 0; }

    /** The side a quarter turn clockwise, seen from above; up and down stay as they are. */
    public Side clockwise() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> this;
        };
    }

    /** The side a quarter turn counterclockwise, seen from above; up and down stay as they are. */
    public Side counterclockwise() {
        return switch (this) {
            case NORTH -> WEST;
            case WEST -> SOUTH;
            case SOUTH -> EAST;
            case EAST -> NORTH;
            default -> this;
        };
    }
}
