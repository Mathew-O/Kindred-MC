package dev.kindred.core.construction;

import dev.kindred.core.construction.BuildSite.Cell;
import dev.kindred.core.construction.BuildSite.Kind;
import dev.kindred.core.construction.ConstructionPlanner.Step;
import dev.kindred.core.construction.Pos.Face;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how a player places a build block by block: the nearest block first, from a spot it
 * can walk to and reach, stairs, slabs, logs and furnaces turned the right way, scaffolding put up to reach
 * high blocks and taken down after, what is in the way cleared without breaking anything not its to break,
 * and never shutting itself in.
 */
class ConstructionPlannerTest {
    private static final Set<String> ALL = Set.of("minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_stairs",
            "minecraft:oak_slab", "minecraft:oak_log", "minecraft:furnace");

    private static Blueprint.Block block(int x, int y, int z, String spec) {
        return new Blueprint.Block(new Pos(x, y, z), BlockSpec.parse(spec));
    }

    private static Step next(List<Blueprint.Block> blocks, Map<Pos, Cell> cells, Pos feet) {
        return ConstructionPlanner.next(blocks, List.of(), new BuildSite(cells), feet, ALL, Set.of());
    }

    private static double reach(Step step) {
        double ex = step.stance().x() + 0.5, ey = step.stance().y() + ConstructionPlanner.EYE, ez = step.stance().z() + 0.5;
        return Math.sqrt(Math.pow(step.hit()[0] - ex, 2) + Math.pow(step.hit()[1] - ey, 2) + Math.pow(step.hit()[2] - ez, 2));
    }

    @Test
    void aFloorGoesDownTheNearestFirstFromWhereItStands() {
        var blocks = List.of(block(3, 1, 0, "minecraft:cobblestone"), block(4, 1, 0, "minecraft:cobblestone"), block(5, 1, 0, "minecraft:cobblestone"));
        var step = next(blocks, BuildTestSites.flat(), new Pos(0, 1, 0));
        assertEquals(ConstructionPlanner.Kind.PLACE, step.kind());
        assertEquals(new Pos(3, 1, 0), step.target(), "the nearest");
        assertEquals(new Pos(3, 0, 0), step.support());
        assertEquals(Face.UP, step.face());
        assertTrue(reach(step) <= ConstructionPlanner.REACH);
        assertEquals(new Pos(0, 1, 0), step.stance(), "from where it stands, when that reaches");
    }

    @Test
    void aSpotWhereTheServerRefusedTheStepIsNotTriedAgain() {
        var blocks = List.of(block(3, 1, 0, "minecraft:cobblestone"));
        var cells = BuildTestSites.flat();
        var first = next(blocks, cells, new Pos(0, 1, 0));
        var again = ConstructionPlanner.next(blocks, List.of(), new BuildSite(cells), new Pos(0, 1, 0), ALL, Set.of(), Set.of(first.stance()));
        assertEquals(ConstructionPlanner.Kind.PLACE, again.kind());
        assertNotEquals(first.stance(), again.stance());
        assertTrue(reach(again) <= ConstructionPlanner.REACH);
    }

    @Test
    void itStandsOnlyWhereItCanWalkTo() {
        var cells = BuildTestSites.flat();
        // A ring of wall two high round the target's corner of the ground: the spots inside are out of its walk.
        for (int x = 2; x <= 8; x++) for (int z = -3; z <= 3; z++) {
            if (x == 2 || x == 8 || z == -3 || z == 3) for (int y = 1; y <= 2; y++) cells.put(new Pos(x, y, z), BuildTestSites.solid("minecraft:stone"));
        }
        var walk = ConstructionPlanner.walkable(new BuildSite(cells), new Pos(0, 1, 0), Set.of());
        assertFalse(walk.contains(new Pos(5, 1, 0)), "inside the ring");
        assertTrue(walk.contains(new Pos(-5, 1, 0)));
        assertFalse(walk.contains(new Pos(2, 3, 0)), "a wall two high is no step up");
        cells.put(new Pos(1, 1, 0), BuildTestSites.solid("minecraft:stone"));
        walk = ConstructionPlanner.walkable(new BuildSite(cells), new Pos(0, 1, 0), Set.of());
        assertTrue(walk.contains(new Pos(2, 3, 0)) && walk.contains(new Pos(5, 1, 0)), "a block before it makes two steps, and over the wall it drops in");
    }

    @Test
    void whatIsAlreadyThereIsDoneAndAllThereIsBuilt() {
        var blocks = List.of(block(3, 1, 0, "minecraft:cobblestone"), block(4, 1, 0, "minecraft:cobblestone"));
        var cells = BuildTestSites.flat();
        cells.put(new Pos(3, 1, 0), BuildTestSites.solid("minecraft:cobblestone"));
        assertEquals(new Pos(4, 1, 0), next(blocks, cells, new Pos(0, 1, 0)).target());
        cells.put(new Pos(4, 1, 0), BuildTestSites.solid("minecraft:cobblestone"));
        assertEquals(ConstructionPlanner.Kind.DONE, next(blocks, cells, new Pos(0, 1, 0)).kind());
    }

    @Test
    void aStairIsPlacedLookingTheWayItFaces() {
        for (String facing : List.of("north", "south", "east", "west")) {
            var blocks = List.of(block(0, 1, 0, "minecraft:oak_stairs[facing=" + facing + ",half=bottom]"));
            var step = next(blocks, BuildTestSites.flat(), new Pos(3, 1, 3));
            assertEquals(ConstructionPlanner.Kind.PLACE, step.kind(), facing);
            assertEquals(facing, Placement.facingOf(step.yaw()).id(), "the look gives the stair's facing");
            assertTrue(reach(step) <= ConstructionPlanner.REACH);
        }
    }

    @Test
    void aTopSlabIsSetAgainstTheUpperHalfOfASideOrUnderABlock() {
        var cells = BuildTestSites.flat();
        cells.put(new Pos(1, 1, 0), BuildTestSites.solid("minecraft:cobblestone"));
        var blocks = List.of(block(0, 1, 0, "minecraft:oak_slab[type=top]"));
        var step = next(blocks, cells, new Pos(0, 1, 3));
        assertEquals(new Pos(1, 1, 0), step.support(), "the block beside it");
        assertEquals(Face.WEST, step.face());
        assertTrue(step.hit()[1] - 1 > 0.5, "the upper half: " + step.hit()[1]);
        // With nothing beside it, a top slab cannot be set on the ground below.
        var alone = next(blocks, BuildTestSites.flat(), new Pos(0, 1, 3));
        assertNotEquals(ConstructionPlanner.Kind.PLACE, alone.kind());
    }

    @Test
    void aLogLiesAlongTheFaceItIsSetAgainst() {
        var cells = BuildTestSites.flat();
        cells.put(new Pos(-1, 1, 0), BuildTestSites.solid("minecraft:cobblestone"));
        var step = next(List.of(block(0, 1, 0, "minecraft:oak_log[axis=x]")), cells, new Pos(0, 1, 3));
        assertEquals(Face.EAST, step.face(), "against the west block's east face: along x");
        var upright = next(List.of(block(0, 1, 0, "minecraft:oak_log[axis=y]")), cells, new Pos(0, 1, 3));
        assertEquals(Face.UP, upright.face());
    }

    @Test
    void aFurnaceFacesBackAtThePlayer() {
        var step = next(List.of(block(0, 1, 0, "minecraft:furnace[facing=north]")), BuildTestSites.flat(), new Pos(2, 1, 2));
        assertEquals(Face.SOUTH, Placement.facingOf(step.yaw()), "looking south, so the furnace faces north at it");
        assertTrue(step.stance().z() < 0, "from the north side: " + step.stance());
    }

    @Test
    void outOfReachAScaffoldIsBuiltToStandOn() {
        var cells = BuildTestSites.flat();
        var blocks = new ArrayList<Blueprint.Block>();
        for (int y = 1; y <= 9; y++) blocks.add(block(0, y, 0, "minecraft:cobblestone"));
        for (int y = 1; y <= 8; y++) cells.put(new Pos(0, y, 0), BuildTestSites.solid("minecraft:cobblestone"));
        var step = next(blocks, cells, new Pos(3, 1, 0));
        assertEquals(ConstructionPlanner.Kind.SCAFFOLD, step.kind(), step.reason());
        assertEquals(5, step.height(), "the lowest column from whose top the block goes on: feet at 6, eyes at 7.62, the face at 9");
        assertEquals(1, step.stance().y());
        // Stood on top of the scaffold, the block goes on.
        var stance = step.stance();
        for (int h = 0; h < step.height(); h++) cells.put(stance.add(0, h, 0), BuildTestSites.solid("minecraft:cobblestone"));
        var up = ConstructionPlanner.next(blocks, List.of(), new BuildSite(cells), stance.add(0, step.height(), 0), ALL, Set.of());
        assertEquals(ConstructionPlanner.Kind.PLACE, up.kind(), up.reason());
        assertEquals(new Pos(0, 9, 0), up.target());
    }

    @Test
    void theScaffoldComesDownWhenTheBuildIsDone() {
        var cells = BuildTestSites.flat();
        var blocks = List.of(block(0, 1, 0, "minecraft:cobblestone"));
        cells.put(new Pos(0, 1, 0), BuildTestSites.solid("minecraft:cobblestone"));
        cells.put(new Pos(2, 1, 0), BuildTestSites.solid("minecraft:cobblestone"));
        cells.put(new Pos(2, 2, 0), BuildTestSites.solid("minecraft:cobblestone"));
        var step = ConstructionPlanner.next(blocks, List.of(), new BuildSite(cells), new Pos(4, 1, 0), ALL, Set.of(new Pos(2, 1, 0), new Pos(2, 2, 0)));
        assertEquals(ConstructionPlanner.Kind.BREAK, step.kind());
        assertEquals(new Pos(2, 2, 0), step.target(), "the top first");
    }

    /**
     * The edge of its ground three blocks over lower ground, as a fixture's field ends (run 5 of 29 October): the only
     * spot in reach of the last scaffold is down off the edge, walkable one way. Stood there, the scaffolding beside it
     * was out of reach and the way back up a wall, all night. A spot it could not walk back to the build from is never
     * chosen.
     */
    @Test
    void neverABreakFromWhereItCouldNotWalkBackToTheBuild() {
        var cells = BuildTestSites.flat();
        // The plateau, z up to 0, three higher than the ground beyond.
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 0; z++) for (int y = 1; y <= 3; y++) cells.put(new Pos(x, y, z), BuildTestSites.STONE);
        // The build, done: a wall two high along z -1.
        var blocks = new java.util.ArrayList<Blueprint.Block>();
        for (int x = -2; x <= 2; x++) for (int y = 4; y <= 5; y++) {
            blocks.add(block(x, y, -1, "minecraft:cobblestone"));
            cells.put(new Pos(x, y, -1), BuildTestSites.solid("minecraft:cobblestone"));
        }
        // Its last scaffold on the plateau's edge row, stone two high either side of it.
        cells.put(new Pos(0, 4, 0), BuildTestSites.solid("minecraft:cobblestone"));
        for (int x : new int[] {-1, 1}) for (int y = 4; y <= 5; y++) cells.put(new Pos(x, y, 0), BuildTestSites.STONE);
        var feet = new Pos(5, 4, 0);
        var walk = ConstructionPlanner.walkable(new BuildSite(cells), feet, Set.of(new Pos(0, 4, 0)));
        assertTrue(walk.contains(new Pos(0, 1, 1)), "down off the edge is walkable one way");
        var step = ConstructionPlanner.next(blocks, List.of(), new BuildSite(cells), feet, ALL, Set.of(new Pos(0, 4, 0)));
        assertTrue(step.kind() != ConstructionPlanner.Kind.BREAK || step.stance().y() >= 4, "never from below the edge: " + step);
        assertEquals(ConstructionPlanner.Kind.BLOCKED, step.kind(), "out of reach from where it can come back: " + step);
    }

    @Test
    void whatIsInTheWayIsBrokenFirstButNeverWhatIsNotTheBuildsToBreak() {
        var cells = BuildTestSites.flat();
        cells.put(new Pos(2, 1, 0), BuildTestSites.solid("minecraft:dirt"));
        var blocks = List.of(block(2, 1, 0, "minecraft:cobblestone"));
        var step = next(blocks, cells, new Pos(0, 1, 0));
        assertEquals(ConstructionPlanner.Kind.BREAK, step.kind());
        assertEquals(new Pos(2, 1, 0), step.target());
        cells.put(new Pos(2, 1, 0), new Cell(Kind.PROTECTED, BlockSpec.of("minecraft:chest"), false));
        assertEquals(ConstructionPlanner.Kind.BLOCKED, next(blocks, cells, new Pos(0, 1, 0)).kind());
    }

    @Test
    void withNothingInHandOrPartOutOfSightItWaits() {
        var blocks = List.of(block(2, 1, 0, "minecraft:stone_bricks"));
        var step = next(blocks, BuildTestSites.flat(), new Pos(0, 1, 0));
        assertEquals(ConstructionPlanner.Kind.BLOCKED, step.kind());
        assertTrue(step.reason().contains("Nothing in hand"), step.reason());
        var far = List.of(block(40, 1, 0, "minecraft:cobblestone"));
        assertTrue(next(far, BuildTestSites.flat(), new Pos(0, 1, 0)).reason().contains("out of sight"));
    }

    @Test
    void itNeverStandsWhereABlockIsStillToGoNorShutsItselfIn() {
        // A ring of wall round the middle, one block short; the player stands in the middle.
        var cells = BuildTestSites.flat();
        var blocks = new ArrayList<Blueprint.Block>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            if (Math.abs(x) != 2 && Math.abs(z) != 2) continue;
            for (int y = 1; y <= 2; y++) {
                blocks.add(block(x, y, z, "minecraft:cobblestone"));
                if (!(x == 0 && z == -2)) cells.put(new Pos(x, y, z), BuildTestSites.solid("minecraft:cobblestone"));
            }
        }
        // Inside, with the gap still open: the next block may not be placed from inside the ring.
        var step = next(blocks, cells, new Pos(0, 1, 0));
        assertEquals(ConstructionPlanner.Kind.PLACE, step.kind(), step.reason());
        assertFalse(Math.abs(step.stance().x()) < 2 && Math.abs(step.stance().z()) < 2, "never from inside, shutting itself in: " + step.stance());
        for (var b : blocks) assertNotEquals(b.at(), step.stance(), "never where a block is to go");
    }

    /**
     * Counts how much is built and which materials are still needed, and lists the blocks it cannot place
     * yet.
     */
    @Test
    void progressAndTheBillCountWhatIsLeftAndListWhatItCannotPlaceYet() {
        var cells = BuildTestSites.flat();
        var blocks = List.of(block(0, 1, 0, "minecraft:cobblestone"), block(1, 1, 0, "minecraft:cobblestone"),
                block(2, 1, 0, "minecraft:wall_torch[facing=east]"), block(3, 1, 0, "minecraft:oak_trapdoor[facing=north,half=bottom]"));
        cells.put(new Pos(0, 1, 0), BuildTestSites.solid("minecraft:cobblestone"));
        var progress = ConstructionPlanner.progress(blocks, List.of(), new BuildSite(cells));
        assertEquals(1, progress.placed());
        assertEquals(List.of("minecraft:oak_trapdoor"), progress.unsupported());
        var bill = BillOfMaterials.of(blocks, new BuildSite(cells), Map.of("minecraft:cobblestone", 4));
        assertEquals(Map.of("minecraft:cobblestone", 1, "minecraft:torch", 1), bill.needed());
        assertEquals(Map.of("minecraft:torch", 1), bill.missing());
        assertEquals(List.of("minecraft:oak_trapdoor"), bill.unsupported());
    }

    /** A finished block, two scaffold columns two high at {@code columns}, on the flat test ground. */
    private static Map<Pos, Cell> scaffolded(List<Blueprint.Block> blocks, java.util.Set<Pos> scaffold, Pos... columns) {
        var cells = BuildTestSites.flat();
        for (var b : blocks) cells.put(b.at(), BuildTestSites.solid(b.spec().block()));
        for (Pos column : columns) for (int h = 0; h < 2; h++) {
            cells.put(column.add(0, h, 0), BuildTestSites.solid("minecraft:dirt"));
            scaffold.add(column.add(0, h, 0));
        }
        return cells;
    }

    /**
     * Built, the scaffolding comes down from the top of each column, the highest first and of equal height the nearest:
     * beside one column, that one, whatever order the scaffold set gives them.
     */
    @Test
    void ofScaffoldTopsAsHighTheNearestComesDownFirst() {
        var blocks = List.of(new Blueprint.Block(new Pos(0, 1, 0), BlockSpec.parse("minecraft:cobblestone")));
        Pos near = new Pos(1, 1, 6), far = new Pos(1, 1, -6);
        for (Pos[] order : new Pos[][] {{near, far}, {far, near}}) {
            var scaffold = new java.util.LinkedHashSet<Pos>();
            var cells = scaffolded(blocks, scaffold, order);
            var step = ConstructionPlanner.next(blocks, List.of(), new BuildSite(cells), new Pos(0, 1, 6), Set.of(), scaffold);
            assertEquals(ConstructionPlanner.Kind.BREAK, step.kind());
            assertEquals(near.up(), step.target(), "the near column's top, listed " + List.of(order));
        }
    }

    /**
     * Scaffolding out of reach from everywhere it can walk to waits while what it can reach comes down; then it is
     * blocked over that column, with the column's place, for the runner to go over to it.
     */
    @Test
    void aColumnOutOfReachFromHereIsLeftForTheRestAndNamed() {
        var blocks = List.of(new Blueprint.Block(new Pos(0, 1, 0), BlockSpec.parse("minecraft:cobblestone")));
        Pos beside = new Pos(1, 1, 6), away = new Pos(1, 1, -10);
        var scaffold = new java.util.LinkedHashSet<Pos>();
        var cells = scaffolded(blocks, scaffold, away, beside);
        // Walled in where it stands, so the far column is in reach of nothing it can walk to.
        Pos feet = new Pos(0, 1, 6);
        for (Face face : List.of(Face.NORTH, Face.SOUTH, Face.WEST)) for (int h = 0; h < 3; h++) cells.put(feet.offset(face).add(0, h, 0), BuildTestSites.solid("minecraft:stone"));
        cells.put(beside.add(0, 2, 0), BuildTestSites.solid("minecraft:stone"));
        var step = ConstructionPlanner.next(blocks, List.of(), new BuildSite(cells), feet, Set.of(), scaffold);
        assertEquals(ConstructionPlanner.Kind.BREAK, step.kind(), step.reason());
        assertEquals(beside.up(), step.target(), "the one in reach first, though the other is listed first");
        // That column down, the wall it made is closed with stone, so the far one is still out of reach.
        scaffold.remove(beside.up());
        scaffold.remove(beside);
        cells.put(beside.up(), BuildTestSites.solid("minecraft:stone"));
        cells.put(beside, BuildTestSites.solid("minecraft:stone"));
        var stuck = ConstructionPlanner.next(blocks, List.of(), new BuildSite(cells), feet, Set.of(), scaffold);
        assertEquals(ConstructionPlanner.Kind.BLOCKED, stuck.kind());
        assertEquals(away.up(), stuck.target(), "named, for the runner to walk over to it");
    }
}
