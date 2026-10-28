package dev.kindred.core.construction;

import dev.kindred.core.construction.BuildSite.Cell;
import dev.kindred.core.construction.BuildSite.Kind;

import java.util.HashMap;
import java.util.Map;

/**
 * In plain terms: a flat patch of stone used as test ground for building tests.
 *
 * Technical: Flat test ground: stone at y 0 across -20..20, air above to y 20, plus whatever a test sets.
 */
final class BuildTestSites {
    static final Cell STONE = new Cell(Kind.SOLID, BlockSpec.of("minecraft:stone"), true);

    private BuildTestSites() { }

    static Map<Pos, Cell> flat() {
        var cells = new HashMap<Pos, Cell>();
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) {
            cells.put(new Pos(x, 0, z), STONE);
            for (int y = 1; y <= 20; y++) cells.put(new Pos(x, y, z), Cell.AIR);
        }
        return cells;
    }

    static Cell solid(String spec) {
        return new Cell(Kind.SOLID, BlockSpec.parse(spec), true);
    }
}
