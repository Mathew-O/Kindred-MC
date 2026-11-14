package dev.kindred.core.construction;

import dev.kindred.core.construction.BuildSite.Cell;
import dev.kindred.core.construction.ConstructionPlanner.Kind;
import dev.kindred.core.construction.ConstructionPlanner.Order;
import dev.kindred.core.construction.ConstructionPlanner.Step;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E-71b1: a builder works from where it stands. Outside the build, a block it can place from its feet goes first, the
 * farthest in reach of them first; only with none does it walk, and then to the spot that serves the most of the layer.
 * Before, each next block was placed from its own best spot, a step to each block of a row it could have placed from one.
 * The row of nine and the two groups are in {@link ConstructionPlannerTest}, beside the floor's order.
 */
class FromOneStanceTest {
    private static final Set<String> ITEMS = Set.of("minecraft:cobblestone");
    private static final Cell COBBLE = BuildTestSites.solid("minecraft:cobblestone");

    private static Blueprint.Block cobble(int x, int y, int z) {
        return new Blueprint.Block(new Pos(x, y, z), BlockSpec.parse("minecraft:cobblestone"));
    }

    /**
     * What building it to the end took: the blocks placed, the walks (a step from a spot not where it stood), where each was
     * placed from and which, in order, and how the plan ended.
     */
    record Built(int placed, int walks, List<Pos> stances, List<Pos> targets, Map<Pos, Cell> cells, Kind end) { }

    /** Every step the plan gives, carried out as given: each placement made from its stance, the feet then there. */
    static Built build(List<Blueprint.Block> blocks, Map<Pos, Cell> cells, Pos feet, Order order) {
        int placed = 0, walks = 0;
        var stances = new ArrayList<Pos>();
        var targets = new ArrayList<Pos>();
        for (int i = 0; i < 500; i++) {
            Step step = ConstructionPlanner.next(blocks, List.of(), new BuildSite(cells), feet, ITEMS, Set.of(), Set.of(), 0, List.of(), order);
            if (step.kind() != Kind.PLACE) return new Built(placed, walks, stances, targets, cells, step.kind());
            if (!step.stance().equals(feet)) walks++;
            feet = step.stance();
            stances.add(feet);
            targets.add(step.target());
            cells.put(step.target(), COBBLE);
            placed++;
        }
        return new Built(placed, walks, stances, targets, cells, null);
    }

    /**
     * Two rows three apart. A spot outside, before the near row, is the shorter walk and serves its seven. A spot between
     * the rows, inside the build's box (+3 to its walk), serves both rows' fourteen. The spot that serves more is taken,
     * though its walk is longer and inside the walls. Once there, where it stands comes first inside the build as outside:
     * the rows' ends stay open, so no block placed from there shuts it in, and all fourteen are placed from that one spot.
     */
    @Test
    void theSpotServingMoreIsTakenThoughItsWalkIsLongerAndInsideTheWalls() {
        var blocks = new ArrayList<Blueprint.Block>();
        for (int x = 0; x <= 6; x++) blocks.add(cobble(x, 1, 0));
        for (int x = 0; x <= 6; x++) blocks.add(cobble(x, 1, 3));
        Pos feet = new Pos(3, 1, -8);
        var box = ConstructionPlanner.Box.of(blocks);
        Step first = ConstructionPlanner.next(blocks, List.of(), new BuildSite(BuildTestSites.flat()), feet, ITEMS, Set.of());
        assertEquals(Kind.PLACE, first.kind());
        Pos between = first.stance();
        assertTrue(box.inside(between) && between.z() > 0 && between.z() < 3, "between the rows: " + between);
        double walkThere = Math.sqrt(between.distanceSquared(feet)) + 3;
        double walkOutside = Math.sqrt(new Pos(3, 1, -1).distanceSquared(feet));
        assertTrue(walkThere > walkOutside, "the longer walk, " + walkThere + " against " + walkOutside);
        var built = build(blocks, BuildTestSites.flat(), feet, Order.FARTHEST_FIRST);
        assertEquals(Kind.DONE, built.end());
        assertEquals(14, built.placed());
        // From between the rows, the farthest first, and no walk after: where it stands serves every block left.
        assertEquals(1, built.walks(), "between the rows, and no walk after: " + built.stances());
        assertEquals(Set.of(between), Set.copyOf(built.stances()), "all fourteen from between the rows");
    }

    /**
     * Farthest first only among blocks it can place now (the lead's condition): a block over a hole, whose only face to be
     * set against is its nearer neighbour's, waits for that neighbour though it is the farther.
     */
    @Test
    void aFartherBlockNeverGoesBeforeItsOwnSupport() {
        var cells = BuildTestSites.flat();
        cells.put(new Pos(6, 0, 0), Cell.AIR);
        var blocks = List.of(cobble(5, 1, 0), cobble(6, 1, 0));
        var built = build(blocks, cells, new Pos(2, 1, 0), Order.FARTHEST_FIRST);
        assertEquals(Kind.DONE, built.end());
        assertEquals(List.of(new Pos(5, 1, 0), new Pos(6, 1, 0)), built.targets(), "its support first");
    }

    /**
     * A hut's walls, five by five and two high, with a gap for a door: every block placed exactly as wanted, the lower
     * course before the upper, all thirty from one spot inside, by the door, which is left open for it to walk out by. On
     * 63a0941c, with where it stands first only outside the build, it took 3 walks. Which order a spot's blocks go in shows
     * in {@link ConstructionPlannerTest}'s row of nine, which nearest first places from three spots.
     */
    @Test
    void aHutsWallsAreBuiltTheSameInFewerWalks() {
        var blocks = new ArrayList<Blueprint.Block>();
        for (int y = 1; y <= 2; y++) for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
            boolean ring = x == 0 || x == 4 || z == 0 || z == 4;
            if (ring && !(x == 2 && z == 0)) blocks.add(cobble(x, y, z));
        }
        var built = build(blocks, BuildTestSites.flat(), new Pos(2, 1, -6), Order.FARTHEST_FIRST);
        assertEquals(Kind.DONE, built.end());
        assertEquals(blocks.size(), built.placed());
        for (var b : blocks) assertTrue(built.cells().get(b.at()).state().sameAs(b.spec()), "placed " + b.at());
        int lastLower = -1, firstUpper = Integer.MAX_VALUE;
        for (int i = 0; i < built.targets().size(); i++) {
            if (built.targets().get(i).y() == 1) lastLower = i;
            else firstUpper = Math.min(firstUpper, i);
        }
        assertTrue(lastLower < firstUpper, "the lower course before the upper: " + built.targets());
        assertEquals(1, built.walks(), "one walk, for " + blocks.size() + " blocks: " + built.stances());
        assertEquals(1, Set.copyOf(built.stances()).size(), "all from one spot: " + built.stances());
        Pos spot = built.stances().getFirst();
        assertEquals(1, Math.abs(spot.x() - 2) + Math.abs(spot.z()), "by the door, its way out: " + spot);
    }
}
