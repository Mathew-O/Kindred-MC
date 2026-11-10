package dev.kindred.core.route;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core route planners' choices pinned on small hand-built terrains (F-35d): the same moves, costs and routes the
 * server's navigator made before it moved into the core, on terrains small enough to work out by hand.
 */
class RoutePlannerTest {
    /** The few blocks these terrains are built of. */
    enum Block { AIR, STONE, WATER, LAVA }

    /** A terrain in memory: air everywhere in its box but where a block is set, and nothing loaded outside it. */
    static final class Box implements Terrain {
        private final Map<Cell, Block> blocks = new HashMap<>();

        Box set(int x, int y, int z, Block block) {
            blocks.put(new Cell(x, y, z), block);
            return this;
        }

        Block at(int x, int y, int z) {
            return blocks.getOrDefault(new Cell(x, y, z), Block.AIR);
        }

        @Override public boolean loaded(int x, int y, int z) {
            return x >= -8 && x <= 30 && y >= -8 && y <= 12 && z >= -8 && z <= 8;
        }
        @Override public Fluid fluid(int x, int y, int z) {
            return switch (at(x, y, z)) {
                case WATER -> Fluid.WATER;
                case LAVA -> Fluid.LAVA;
                default -> Fluid.NONE;
            };
        }
        @Override public boolean harms(int x, int y, int z) { return at(x, y, z) == Block.LAVA; }
        @Override public boolean passThrough(int x, int y, int z) { return at(x, y, z) != Block.STONE; }
        @Override public boolean supports(int x, int y, int z) { return at(x, y, z) == Block.STONE; }
        @Override public boolean bears(int x, int y, int z) { return at(x, y, z) == Block.STONE; }
        @Override public boolean scorches(int x, int y, int z) { return false; }
        @Override public boolean bottomHalf(int x, int y, int z) { return false; }
        @Override public boolean portal(int x, int y, int z) { return false; }
        @Override public boolean replaceable(int x, int y, int z) { return at(x, y, z) != Block.STONE; }
        @Override public float hardness(int x, int y, int z) { return at(x, y, z) == Block.STONE ? 1.5f : at(x, y, z) == Block.AIR ? 0 : 100; }
        @Override public boolean placeAgainst(int x, int y, int z) { return loaded(x, y, z) && at(x, y, z) == Block.STONE; }
    }

    /** A floor of stone one wide along x, at y -1, from {@code from} to {@code to}, but for the gaps given. */
    private static Box corridor(int from, int to, int... gaps) {
        var box = new Box();
        outer:
        for (int x = from; x <= to; x++) {
            for (int gap : gaps) if (gap == x) continue outer;
            box.set(x, -1, 0, Block.STONE);
        }
        return box;
    }

    private static List<Cell> line(int y, int... xs) {
        return java.util.Arrays.stream(xs).mapToObj(x -> new Cell(x, y, 0)).toList();
    }

    private static Cell at(int x, int y, int z) { return new Cell(x, y, z); }

    @Test
    void aFlatWalkGoesStraightThere() {
        var path = new Navigator(corridor(0, 6)).find(at(0, 0, 0), at(4, 0, 0), at(4, 0, 0)::equals, Navigator.DEFAULT_MAX_NODES, 48);
        assertTrue(path.complete());
        assertEquals(line(0, 0, 1, 2, 3, 4), path.nodes());
        assertEquals(5, path.expanded());
    }

    @Test
    void aBlockInTheWayIsSteppedUpOntoAndDroppedOffAgain() {
        var box = corridor(0, 6).set(2, 0, 0, Block.STONE);
        var navigator = new Navigator(box);
        assertEquals(List.of(new Navigator.Move(at(2, 1, 0), 2.0)),
                navigator.moves(at(1, 0, 0)).stream().filter(m -> m.to().x() == 2).toList());
        var path = navigator.find(at(0, 0, 0), at(4, 0, 0), at(4, 0, 0)::equals, Navigator.DEFAULT_MAX_NODES, 48);
        assertTrue(path.complete());
        assertEquals(List.of(at(0, 0, 0), at(1, 0, 0), at(2, 1, 0), at(3, 0, 0), at(4, 0, 0)), path.nodes());
    }

    @Test
    void aDropOfThreeIsTakenAndADropOfFourRefused() {
        // A ledge at 0 to 1, and ground three below it from 2 on.
        var three = corridor(0, 1);
        for (int x = 2; x <= 4; x++) three.set(x, -4, 0, Block.STONE);
        var down = new Navigator(three).find(at(1, 0, 0), at(3, -3, 0), at(3, -3, 0)::equals, Navigator.DEFAULT_MAX_NODES, 48);
        assertTrue(down.complete());
        assertEquals(List.of(at(1, 0, 0), at(2, -3, 0), at(3, -3, 0)), down.nodes());
        assertEquals(List.of(new Navigator.Move(at(2, -3, 0), 1.0 + 3 * 0.5), new Navigator.Move(at(0, 0, 0), 1.0)),
                new Navigator(three).moves(at(1, 0, 0)));

        // The same, four below: no move off the edge at all.
        var four = corridor(0, 1);
        for (int x = 2; x <= 4; x++) four.set(x, -5, 0, Block.STONE);
        var refused = new Navigator(four).find(at(1, 0, 0), at(3, -4, 0), at(3, -4, 0)::equals, Navigator.DEFAULT_MAX_NODES, 48);
        assertFalse(refused.complete());
        assertEquals(List.of(new Navigator.Move(at(0, 0, 0), 1.0)), new Navigator(four).moves(at(1, 0, 0)));
        assertEquals(List.of(at(1, 0, 0)), refused.nodes());
    }

    @Test
    void aPoolTwoDeepIsSwumAcrossAtTheSurface() {
        var box = corridor(0, 0);
        for (int x = 1; x <= 3; x++) {
            box.set(x, -2, 0, Block.STONE).set(x, -1, 0, Block.WATER).set(x, 0, 0, Block.WATER);
        }
        box.set(4, -1, 0, Block.STONE).set(5, -1, 0, Block.STONE);
        var navigator = new Navigator(box);
        assertTrue(navigator.swimmable(at(2, 0, 0)) && !navigator.standable(at(2, 0, 0)));
        // Along the surface at three a block; down at six; no rise into the air over it, where it could not stand.
        assertEquals(List.of(new Navigator.Move(at(3, 0, 0), 3.0), new Navigator.Move(at(1, 0, 0), 3.0),
                new Navigator.Move(at(2, -1, 0), 6.0)), navigator.moves(at(2, 0, 0)));
        var path = navigator.find(at(0, 0, 0), at(5, 0, 0), at(5, 0, 0)::equals, Navigator.DEFAULT_MAX_NODES, 48);
        assertTrue(path.complete());
        assertEquals(line(0, 0, 1, 2, 3, 4, 5), path.nodes());
    }

    @Test
    void gapsOfOneAndTwoAreJumpedOnlyAsWideAsAllowed() {
        // One wide, a one-block gap at 3, a two-block gap at 7 and 8: the fixture's course.
        var box = corridor(-1, 12, 3, 7, 8);
        var both = new Navigator(box).jumpingGaps(2).find(at(0, 0, 0), at(11, 0, 0), at(11, 0, 0)::equals, Navigator.DEFAULT_MAX_NODES, 48);
        assertTrue(both.complete());
        assertEquals(line(0, 0, 1, 2, 4, 5, 6, 9, 10, 11), both.nodes());
        assertTrue(new Navigator(box).jumpingGaps(2).moves(at(2, 0, 0)).contains(new Navigator.Move(at(4, 0, 0), 3.0)));
        assertTrue(new Navigator(box).jumpingGaps(2).moves(at(6, 0, 0)).contains(new Navigator.Move(at(9, 0, 0), 5.0)));

        var one = new Navigator(box).jumpingGaps(1).find(at(0, 0, 0), at(11, 0, 0), at(11, 0, 0)::equals, Navigator.DEFAULT_MAX_NODES, 48);
        assertFalse(one.complete());
        assertEquals(line(0, 0, 1, 2, 4, 5, 6), one.nodes());

        var none = new Navigator(box).find(at(0, 0, 0), at(11, 0, 0), at(11, 0, 0)::equals, Navigator.DEFAULT_MAX_NODES, 48);
        assertFalse(none.complete());
        assertEquals(line(0, 0, 1, 2), none.nodes());
    }

    @Test
    void aWallTwoHighAcrossTheOnlyWayIsDugThrough() {
        var box = corridor(0, 6).set(3, 0, 0, Block.STONE).set(3, 1, 0, Block.STONE);
        var planner = new RoutePlanner(new Navigator(box), new RoutePlanner.Allowance(cell -> true, cell -> true, 0));
        var route = planner.find(at(0, 0, 0), at(5, 0, 0), at(5, 0, 0)::equals, 3000, 48);
        assertTrue(route.complete());
        assertEquals(List.of(RoutePlanner.Kind.WALK, RoutePlanner.Kind.WALK, RoutePlanner.Kind.DIG, RoutePlanner.Kind.WALK, RoutePlanner.Kind.WALK),
                route.steps().stream().map(RoutePlanner.Step::kind).toList());
        assertEquals(new RoutePlanner.Step(at(2, 0, 0), at(3, 0, 0), RoutePlanner.Kind.DIG, List.of(at(3, 0, 0), at(3, 1, 0))),
                route.steps().get(2));
        assertEquals(2, route.firstEdit());

        // Without leave to dig, nothing gets past it.
        var walled = new RoutePlanner(new Navigator(box), RoutePlanner.Allowance.none()).find(at(0, 0, 0), at(5, 0, 0), at(5, 0, 0)::equals, 3000, 48);
        assertFalse(walled.complete());
        assertEquals(-1, walled.firstEdit());
    }

    @Test
    void aShaftWithALedgeAtItsTopIsPillaredUp() {
        // A shaft one wide, three high, walled on every side; its east wall's top is the ledge to stand on.
        var box = new Box().set(0, -1, 0, Block.STONE);
        for (int y = 0; y <= 2; y++) {
            box.set(1, y, 0, Block.STONE).set(-1, y, 0, Block.STONE).set(0, y, 1, Block.STONE).set(0, y, -1, Block.STONE);
        }
        var planner = new RoutePlanner(new Navigator(box), new RoutePlanner.Allowance(cell -> false, cell -> false, 8));
        var route = planner.find(at(0, 0, 0), at(1, 3, 0), at(1, 3, 0)::equals, 3000, 48);
        assertTrue(route.complete());
        assertEquals(List.of(
                new RoutePlanner.Step(at(0, 0, 0), at(0, 1, 0), RoutePlanner.Kind.PILLAR, List.of(at(0, 0, 0))),
                new RoutePlanner.Step(at(0, 1, 0), at(0, 2, 0), RoutePlanner.Kind.PILLAR, List.of(at(0, 1, 0))),
                new RoutePlanner.Step(at(0, 2, 0), at(1, 3, 0), RoutePlanner.Kind.WALK, List.of())), route.steps());

        // With the shaft's cells set aside, no block goes in it.
        var refused = new RoutePlanner(new Navigator(box), new RoutePlanner.Allowance(cell -> false, cell -> cell.x() == 0, 8))
                .find(at(0, 0, 0), at(1, 3, 0), at(1, 3, 0)::equals, 3000, 48);
        assertFalse(refused.complete());
        assertTrue(refused.steps().isEmpty());
    }

    @Test
    void aSearchOutOfNodesReturnsThePartWayItFound() {
        var box = corridor(0, 25);
        var path = new Navigator(box).find(at(0, 0, 0), at(20, 0, 0), at(20, 0, 0)::equals, 5, 48);
        assertFalse(path.complete());
        assertEquals(5, path.expanded());
        assertEquals(line(0, 0, 1, 2, 3, 4), path.nodes());

        var route = new RoutePlanner(new Navigator(box), RoutePlanner.Allowance.none()).find(at(0, 0, 0), at(20, 0, 0), at(20, 0, 0)::equals, 5, 48);
        assertFalse(route.complete());
        assertEquals(5, route.expanded());
        assertEquals(4, route.steps().size());
        assertEquals(at(4, 0, 0), route.steps().getLast().to());
    }

    @Test
    void theDirectionOrdersAreTheGamesOwn() {
        assertEquals(List.of(Side.DOWN, Side.UP, Side.NORTH, Side.SOUTH, Side.WEST, Side.EAST), List.of(Side.values()));
        assertEquals(List.of(Side.NORTH, Side.EAST, Side.SOUTH, Side.WEST), Side.HORIZONTAL);
        assertEquals(at(0, 0, -1), at(0, 0, 0).offset(Side.NORTH));
        assertEquals(at(1, 0, 0), at(0, 0, 0).offset(Side.EAST));
        assertEquals(Side.EAST, Side.NORTH.clockwise());
        assertEquals(Side.WEST, Side.NORTH.counterclockwise());
    }

    @Test
    void cellKeysPackAsTheGameDoes() {
        // The game's own packing of a block position: x in the top 26 bits, then z in 26, then y in 12.
        assertEquals(((long) 5 & 0x3FFFFFF) << 38 | ((long) -7 & 0x3FFFFFF) << 12 | ((long) -60 & 0xFFF), at(5, -60, -7).asLong());
        assertFalse(at(1, 2, 3).asLong() == at(1, 3, 2).asLong());
    }

    @Test
    void theRoutePackageHasNoMinecraftInIt() throws IOException {
        Path sources = Path.of("src/main/java/dev/kindred/core/route");
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("net.minecraft") || text.contains("net.fabricmc") || text.contains("minecraft:"),
                        file + " names Minecraft");
            }
        }
    }
}
