package dev.kindred.core.construction;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: what a builder sees around its build: for every block in a box around the site, whether it
 * is air, a block it can build over, solid, walkable, dangerous or protected. Anything outside the box is
 * unknown, and nothing is ever built against the unknown.
 *
 * Technical: What the world holds round a build, as the adapter saw it on the server thread (E-08a): for each
 * cell in a bounded box, what kind of thing is there and its exact state. A cell outside the snapshot is
 * unknown, and nothing is built against what is unknown. Immutable and pure.
 */
public final class BuildSite {
    /** How a cell bears on building. */
    public enum Kind {
        /** Nothing there. */
        AIR,
        /** Something a placed block takes the place of: grass, a flower, snow, water. */
        REPLACEABLE,
        /** Something with a collision box: a block to click against, and to stand on when its top is full. */
        SOLID,
        /** Something without collision that is not replaced either: a torch, a rail, a ladder. Walked through. */
        PASSABLE,
        /** Lava or fire: never stood in, never built into. */
        DANGER,
        /** Not the build's to change: a container, another's claim, a protected area. */
        PROTECTED
    }

    /**
     * One cell.
     *
     * @param kind   how it bears on building
     * @param state  the block there, in its exact state
     * @param floor  its top is a floor to stand on
     */
    public record Cell(Kind kind, BlockSpec state, boolean floor) {
        public Cell {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(state);
        }

        public static final Cell AIR = new Cell(Kind.AIR, BlockSpec.of("minecraft:air"), false);

        /** Feet and head may be in it. */
        public boolean passable() {
            return kind == Kind.AIR || kind == Kind.REPLACEABLE && !state.block().equals("minecraft:water") || kind == Kind.PASSABLE;
        }

        /** A placed block may go into it. */
        public boolean placeable() {
            return kind == Kind.AIR || kind == Kind.REPLACEABLE;
        }

        /** Something to click a face of. */
        public boolean clickable() {
            return kind == Kind.SOLID;
        }
    }

    private final Map<Pos, Cell> cells;
    /** Cells changed over {@link #cells}: what a placement or a scaffold would make of them. */
    private final Map<Pos, Cell> changes;

    /** A view of the site from what the game saw. */
    public BuildSite(Map<Pos, Cell> cells) {
        if (cells.size() > 400_000) throw new IllegalArgumentException("Snapshot too large");
        this.cells = Map.copyOf(cells);
        this.changes = Map.of();
    }

    private BuildSite(Map<Pos, Cell> cells, Map<Pos, Cell> changes) {
        this.cells = cells;
        this.changes = changes;
    }

    /** What is at a position, if it is inside the view. */
    public Optional<Cell> cell(Pos at) {
        var changed = changes.get(at);
        return Optional.ofNullable(changed != null ? changed : cells.get(at));
    }

    /** Whether a position is inside the view. */
    public boolean known(Pos at) {
        return changes.containsKey(at) || cells.containsKey(at);
    }

    /** What the world will hold once some cells change, those blocks placed: the snapshot itself is shared, not copied. */
    public BuildSite with(Map<Pos, Cell> more) {
        var next = new HashMap<>(changes);
        next.putAll(more);
        return new BuildSite(cells, Map.copyOf(next));
    }

    /** Feet may stand here: a floor under, room for feet and head, nothing dangerous. */
    public boolean standable(Pos feet) {
        var below = cell(feet.down());
        var at = cell(feet);
        var head = cell(feet.up());
        return below.isPresent() && below.get().floor() && at.isPresent() && at.get().passable() && head.isPresent() && head.get().passable();
    }

    /** How many positions the view covers. */
    public int size() {
        int added = 0;
        for (Pos p : changes.keySet()) if (!cells.containsKey(p)) added++;
        return cells.size() + added;
    }
}
