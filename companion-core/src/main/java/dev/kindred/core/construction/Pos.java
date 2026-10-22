package dev.kindred.core.construction;

/** A block position, in a blueprint's own frame or the world's (E-08a). Pure. */
public record Pos(int x, int y, int z) implements Comparable<Pos> {
    public Pos add(int dx, int dy, int dz) {
        return new Pos(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }

    public Pos add(Pos other) {
        return add(other.x, other.y, other.z);
    }

    public Pos up() { return add(0, 1, 0); }

    public Pos down() { return add(0, -1, 0); }

    /** The neighbour on {@code face}: one of up, down, north, south, east, west. */
    public Pos offset(Face face) {
        return add(face.dx, face.dy, face.dz);
    }

    /** Squared distance between block centres. */
    public long distanceSquared(Pos other) {
        long dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Bottom first, then north to south, then west to east: the order a build goes up in. */
    @Override public int compareTo(Pos other) {
        int c = Integer.compare(y, other.y);
        if (c == 0) c = Integer.compare(z, other.z);
        return c == 0 ? Integer.compare(x, other.x) : c;
    }

    /** The six faces of a block, as Minecraft names them. */
    public enum Face {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

        public final int dx, dy, dz;

        Face(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        public Face opposite() {
            return switch (this) {
                case DOWN -> UP; case UP -> DOWN; case NORTH -> SOUTH; case SOUTH -> NORTH; case WEST -> EAST; case EAST -> WEST;
            };
        }

        public boolean horizontal() { return dy == 0; }

        /** The axis it lies along: x, y or z. */
        public String axis() { return dx != 0 ? "x" : dy != 0 ? "y" : "z"; }

        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
}
