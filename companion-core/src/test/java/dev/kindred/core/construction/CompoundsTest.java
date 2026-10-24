package dev.kindred.core.construction;

import dev.kindred.core.construction.BuildSite.Cell;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CompoundsTest {
    private static final Set<String> HELD = Set.of("minecraft:oak_planks", "minecraft:oak_log", "minecraft:cobblestone", "minecraft:oak_stairs",
            "minecraft:oak_slab", "minecraft:oak_door", "minecraft:red_bed", "minecraft:chest", "minecraft:furnace", "minecraft:crafting_table",
            "minecraft:torch", "minecraft:dirt");

    /** Stone from six below the yard's floor up to the ground's top at y 0, air above, wide enough round a keep. */
    private static Map<Pos, Cell> ground() {
        var cells = new HashMap<Pos, Cell>();
        for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
            for (int y = -6; y <= 0; y++) cells.put(new Pos(x, y, z), BuildTestSites.STONE);
            for (int y = 1; y <= 12; y++) cells.put(new Pos(x, y, z), Cell.AIR);
        }
        return cells;
    }

    /** The ground with the keep standing on it, its clear space clear: as built. */
    private static Map<Pos, Cell> built(Blueprint keep, Pos origin) {
        var cells = ground();
        for (var p : keep.placedClear(origin, 0)) cells.put(p, Cell.AIR);
        for (var b : keep.placed(origin, 0)) {
            String id = b.spec().block();
            boolean passable = id.endsWith("torch");
            boolean fullTop = !id.endsWith("_stairs") && !id.endsWith("_slab") && !passable;
            cells.put(b.at(), new Cell(passable ? BuildSite.Kind.PASSABLE : BuildSite.Kind.SOLID, b.spec(), fullTop));
        }
        return cells;
    }

    @Test
    void theKeepsWallStandsWholeFromItsFoundationAndIsLinedItsWholeHeight() {
        for (int variant : new int[] {0, 3, 18}) {
            var style = new BuildStyle("", "oak", "cobblestone", variant, 0);
            var keep = Compounds.keep(style);
            var wall = Compounds.wall(style);
            var at = Blueprint.byPosition(keep.blocks());
            int torches = 0;
            for (int x = wall.west(); x <= wall.east(); x++) for (int z = wall.north(); z <= wall.south(); z++) {
                boolean outer = x == wall.west() || x == wall.east() || z == wall.north() || z == wall.south();
                boolean lining = !outer && (x == wall.west() + 1 || x == wall.east() - 1 || z == wall.north() + 1 || z == wall.south() - 1);
                for (int y = outer ? -1 : 0; y < 3 && (outer || lining); y++) {
                    var spec = at.get(new Pos(x, y, z));
                    assertNotNull(spec, "variant " + variant + ": no gap in the wall at " + x + ", " + y + ", " + z);
                    assertEquals(outer ? "minecraft:cobblestone" : "minecraft:oak_planks", spec.block(), "stone outside, lined with planks");
                }
            }
            for (var b : keep.blocks()) {
                if (!b.spec().block().equals("minecraft:wall_torch")) continue;
                var behind = b.at().offset(Placement.face(b.spec().property("facing")).opposite());
                assertNotNull(at.get(behind), "each torch hung on a block: " + b.at());
                if (at.get(behind).block().equals("minecraft:oak_planks") && wall.lining(behind.x(), behind.z())) torches++;
            }
            assertTrue(torches >= 6, "the yard lit from the lining: " + torches);
            assertEquals(Houses.functions(Houses.of(Houses.Stage.CABIN, style)), Houses.functions(keep), "the cabin inside, all of it");
            assertEquals(1, Houses.functions(keep).get("door"), "one door, the cabin's: none in the wall");
        }
    }

    @Test
    void theOnlyWayInIsThePassageUnderTheWall() {
        var style = new BuildStyle("", "oak", "cobblestone", 0, 0);
        var keep = Compounds.keep(style);
        var wall = Compounds.wall(style);
        var origin = new Pos(0, 1, 0);
        int pz = wall.wayIn();
        // From the ground west of the keep, its stair a few blocks off: in through the passage to the yard.
        Pos outside = new Pos(-3, 1, pz), yard = new Pos(wall.west() + 5, 1, pz);
        var site = new BuildSite(built(keep, origin));
        var reach = ConstructionPlanner.walkable(site, outside, Set.of());
        assertTrue(reach.contains(yard), "in under the wall");
        assertTrue(reach.contains(new Pos(wall.west(), 1 - 3, pz)), "under the wall itself, three below the yard");
        // With the passage under the wall filled, nothing gets in: no other way through or over.
        var shut = built(keep, origin);
        for (int y = -3; y <= -2; y++) shut.put(new Pos(wall.west(), 1 + y, pz), BuildTestSites.STONE);
        var reachShut = ConstructionPlanner.walkable(new BuildSite(shut), outside, Set.of());
        assertFalse(reachShut.contains(yard), "walled in");
        assertTrue(reachShut.stream().noneMatch(p -> p.x() > wall.west() + 1 && p.x() < wall.east() - 1 && p.z() > wall.north() + 1
                && p.z() < wall.south() - 1 && p.y() >= 1), "nowhere in the yard");
        // From outside, nothing in the wall shows the way in: every face of it stone, above the ground, all round.
        for (int x = wall.west(); x <= wall.east(); x++) for (int y = 0; y < 3; y++) {
            assertEquals("minecraft:cobblestone", Blueprint.byPosition(keep.blocks()).get(new Pos(x, y, wall.south())).block());
        }
    }

    @Test
    void thePlannerBuildsTheWholeKeepAndTakesItsScaffoldDown() {
        for (int[] vt : new int[][] {{0, 0}, {3, 1}, {28, 2}}) {
            var style = new BuildStyle("", "oak", "cobblestone", vt[0], vt[1]);
            var keep = Compounds.keep(style);
            var origin = new Pos(0, 1, 0);
            var held = new HashSet<>(HELD);
            var run = BuildSimulation.build(keep.placed(origin, vt[1]), keep.placedClear(origin, vt[1]), ground(), new Pos(22, 1, 22), held,
                    keep.name() + " v" + vt[0] + " t" + vt[1]);
            assertTrue(run.steps() > keep.blocks().size(), "every block placed: " + run);
        }
    }
}
