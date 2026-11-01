package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: checks how a player plans a rail line to bring a villager by minecart into its cell in the
 * trading hall: the line takes as few corners as it can, goes round what it cannot lay rails on, bridges
 * water, climbs only on straight stretches, leaves room behind the villager to push the cart off, and never
 * doubles back beside itself.
 */
class RailRouteTest {
    /** Flat firm ground at 63, open over it, with {@code changes} by column: a top, or a column that is no ground. */
    private static BaseSite.Ground ground(Map<Long, BaseSite.Column> changes) {
        return (x, z) -> Optional.of(changes.getOrDefault(key(x, z), new BaseSite.Column(63, true, 8)));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static final Pos HALL = new Pos(0, 64, 0);

    private static int turns(java.util.List<Pos> rails) {
        int turns = 0;
        for (int i = 2; i < rails.size(); i++) {
            int ax = rails.get(i - 1).x() - rails.get(i - 2).x(), az = rails.get(i - 1).z() - rails.get(i - 2).z();
            int bx = rails.get(i).x() - rails.get(i - 1).x(), bz = rails.get(i).z() - rails.get(i - 1).z();
            if (ax != bx || az != bz) turns++;
        }
        return turns;
    }

    private static void connected(java.util.List<Pos> rails) {
        for (int i = 1; i < rails.size(); i++) {
            var a = rails.get(i - 1);
            var b = rails.get(i);
            assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()), "One column from the last: " + a + " to " + b);
            assertTrue(Math.abs(a.y() - b.y()) <= 1, "A block up or down at most: " + a + " to " + b);
        }
    }

    @Test
    void aVillagerInLineWithTheDoorIsBroughtStraightInToTheMiddleCell() {
        var cell = TradingHall.cells(3).get(1);
        var route = RailRoute.plan(ground(Map.of()), 3, 20, HALL, 3, cell).orElseThrow();
        var rails = route.rails();
        connected(rails);
        assertEquals(0, turns(rails), "A straight line: " + rails);
        assertEquals(new Pos(3, 64, 22), route.cart(), "The cart two behind the villager");
        assertEquals(new Pos(3, 64, 20), route.villager());
        assertEquals(new Pos(3, 64, 23), route.push(), "The push from behind the cart");
        // Up into the doorway from the ground, on through the corridor and the cell's front onto its floor.
        assertEquals(new Pos(3, 64, 8), rails.get(rails.size() - 6));
        assertEquals(new Pos(3, 65, 7), rails.get(rails.size() - 5), "The doorway, a block up from the ground");
        assertEquals(new Pos(cell.stand().x(), 65, cell.stand().z()), rails.getLast(), "The cell's floor");
        assertEquals(2 + 13 + 5, rails.size());
        assertEquals(Map.of("minecraft:rail", 20, "minecraft:iron_ingot", 12, "minecraft:stick", 2), RailRoute.bill(route));
    }

    @Test
    void aVillagerOffToOneSideIsBroughtRoundOneCornerAndStraightInAtTheDoor() {
        var cell = TradingHall.cells(3).get(0);
        var route = RailRoute.plan(ground(Map.of()), 12, 20, HALL, 3, cell).orElseThrow();
        var rails = route.rails();
        connected(rails);
        int outside = rails.size() - RailRoute.inside(3, cell).size();
        assertEquals(1, turns(rails.subList(0, outside)), "One corner over the ground: " + rails);
        // Its last stretch outside runs north to the door.
        for (int i = outside - 3; i < outside; i++) assertEquals(3, rails.get(i).x(), "Straight in at the door: " + rails.get(i));
        // In the hall: along the corridor's south row to the first cell, and north into it.
        var inside = rails.subList(outside, rails.size());
        assertEquals(new Pos(3, 65, 7), inside.get(0));
        assertEquals(new Pos(3, 65, 6), inside.get(1));
        assertEquals(new Pos(1, 65, 6), inside.get(3));
        assertEquals(new Pos(1, 65, 3), inside.getLast());
        assertEquals(2, turns(inside), "A corner in the corridor and one into the cell");
    }

    @Test
    void aLineGoesRoundWhatItCannotLayOnAndClimbsOnlyOnAStraight() {
        var cell = TradingHall.cells(3).get(1);
        var changes = new HashMap<Long, BaseSite.Column>();
        // Lava across the way, but for a gap at its west end.
        for (int x = -2; x <= 8; x++) changes.put(key(x, 14), new BaseSite.Column(62, false, 8));
        var round = RailRoute.plan(ground(changes), 3, 20, HALL, 3, cell).orElseThrow();
        connected(round.rails());
        assertTrue(round.rails().stream().noneMatch(p -> p.z() == 14 && p.x() >= -2 && p.x() <= 8), "Never over the lava: " + round.rails());
        assertTrue(round.bridge().isEmpty(), "No bridge: " + round.bridge());
        // Closed right across: no line.
        for (int x = -60; x <= 60; x++) changes.put(key(x, 14), new BaseSite.Column(62, false, 8));
        assertTrue(RailRoute.plan(ground(changes), 3, 20, HALL, 3, cell).isEmpty(), "No way across");

        // A step a block up across the way: climbed on the straight.
        var step = new HashMap<Long, BaseSite.Column>();
        for (int x = -60; x <= 60; x++) for (int z = 9; z <= 16; z++) step.put(key(x, z), new BaseSite.Column(64, true, 8));
        // The ground by the door back down to the hall's level, as BaseSite leaves it.
        for (int x = -60; x <= 60; x++) step.put(key(x, 8), new BaseSite.Column(63, true, 8));
        var climb = RailRoute.plan(ground(step), 3, 20, HALL, 3, cell).orElseThrow();
        connected(climb.rails());
        assertEquals(0, turns(climb.rails()), "Up and down again in a straight line: " + climb.rails());
    }

    @Test
    void aLineCrossesWaterOnABridgeAndGoesRoundAPuddleOnlyWhereThatIsNoDearer() {
        var cell = TradingHall.cells(3).get(1);
        // A river right across the way, three wide, its water's top level with the ground's.
        var river = new HashMap<Long, BaseSite.Column>();
        for (int x = -60; x <= 60; x++) for (int z = 13; z <= 15; z++) river.put(key(x, z), new BaseSite.Column(63, false, 8, true));
        var across = RailRoute.plan(ground(river), 3, 20, HALL, 3, cell).orElseThrow();
        connected(across.rails());
        assertEquals(0, turns(across.rails().subList(0, across.rails().size() - RailRoute.inside(3, cell).size())), "Straight over: " + across.rails());
        assertEquals(java.util.List.of(new Pos(3, 63, 15), new Pos(3, 63, 14), new Pos(3, 63, 13)), across.bridge(), "A block at the water's top a column, in the line's order");
        for (int z = 13; z <= 15; z++) assertTrue(across.rails().contains(new Pos(3, 64, z)), "Its rail on the bridge, level with the ground's");
        // The water too deep down to bridge level with the ground on either side: a block up or down at most.
        var low = new HashMap<Long, BaseSite.Column>();
        for (int x = -60; x <= 60; x++) for (int z = 13; z <= 15; z++) low.put(key(x, z), new BaseSite.Column(61, false, 8, true));
        assertTrue(RailRoute.plan(ground(low), 3, 20, HALL, 3, cell).isEmpty(), "No line down to water two below the ground");
        // A puddle one column wide on the way, with dry ground beside: over it, a block dearer, rather than round it,
        // four rails dearer.
        var puddle = new HashMap<Long, BaseSite.Column>();
        puddle.put(key(3, 14), new BaseSite.Column(63, false, 8, true));
        var over = RailRoute.plan(ground(puddle), 3, 20, HALL, 3, cell).orElseThrow();
        assertEquals(java.util.List.of(new Pos(3, 63, 14)), over.bridge());
        // Water by the villager: the cart's place and the push are never on a bridge.
        var behind = new HashMap<Long, BaseSite.Column>();
        for (int x = -60; x <= 60; x++) for (int z = 21; z <= 24; z++) behind.put(key(x, z), new BaseSite.Column(63, false, 8, true));
        assertTrue(RailRoute.plan(ground(behind), 3, 20, HALL, 3, cell).map(r -> r.cart().z() < 21 && r.push().z() < 21).orElse(true),
                "No cart put down, nor a push begun, over the water behind it");
    }

    @Test
    void noLineToADoorTheGroundIsNotLevelWith() {
        var cell = TradingHall.cells(3).get(1);
        var changes = new HashMap<Long, BaseSite.Column>();
        changes.put(key(3, 8), new BaseSite.Column(62, true, 8));
        assertTrue(RailRoute.plan(ground(changes), 3, 20, HALL, 3, cell).isEmpty(), "The door's ground two below the hall's floor");
        // Nor from a villager with no room behind it for the cart.
        var walled = new HashMap<Long, BaseSite.Column>();
        walled.put(key(3, 21), new BaseSite.Column(63, true, 1));
        walled.put(key(4, 20), new BaseSite.Column(63, true, 1));
        walled.put(key(2, 20), new BaseSite.Column(63, true, 1));
        assertTrue(RailRoute.plan(ground(walled), 3, 20, HALL, 3, cell).isEmpty(), "No room for the cart behind it");
    }

    @Test
    void aVillagerInABoatInTheCartNeedsARowOfThreeOpenOverEveryRail() {
        var cell = TradingHall.cells(3).get(1);
        // An overhang across the way, two open under it: under it in the cart; round it in a boat in the cart.
        var low = new HashMap<Long, BaseSite.Column>();
        for (int x = -2; x <= 8; x++) low.put(key(x, 14), new BaseSite.Column(63, true, 2));
        var cart = RailRoute.plan(ground(low), 3, 20, HALL, 3, cell, RailRoute.CART_ROOM).orElseThrow();
        assertTrue(cart.rails().contains(new Pos(3, 64, 14)), "Straight under it in the cart: " + cart.rails());
        assertEquals(cart, RailRoute.plan(ground(low), 3, 20, HALL, 3, cell).orElseThrow(), "The cart's room is the default");
        var boat = RailRoute.plan(ground(low), 3, 20, HALL, 3, cell, RailRoute.BOAT_ROOM).orElseThrow();
        connected(boat.rails());
        assertTrue(boat.rails().stream().noneMatch(p -> p.z() == 14 && p.x() >= -2 && p.x() <= 8), "Round it in a boat: " + boat.rails());
        // Across the whole way: no line for a boat.
        for (int x = -60; x <= 60; x++) low.put(key(x, 14), new BaseSite.Column(63, true, 2));
        assertTrue(RailRoute.plan(ground(low), 3, 20, HALL, 3, cell, RailRoute.BOAT_ROOM).isEmpty(), "No room for a villager in a boat");
        assertThrows(IllegalArgumentException.class, () -> RailRoute.plan(ground(Map.of()), 3, 20, HALL, 3, cell, 1));
        // A run-up of three for the boat, whose box reaches further back than a villager's: the cart three behind, the push four.
        var open = RailRoute.plan(ground(Map.of()), 3, 20, HALL, 3, cell, RailRoute.BOAT_ROOM).orElseThrow();
        assertEquals(new Pos(3, 64, 23), open.cart());
        assertEquals(new Pos(3, 64, 24), open.push());
        assertEquals(new Pos(3, 64, 21), open.rails().get(2), "The rail before the villager's");
        assertEquals(new Pos(3, 64, 20), open.rails().get(3), "The villager's");
    }

    @Test
    void theLineSetsOutOnlyWhereThereIsRoomBehindTheVillagerForTheRunUp() {
        var cell = TradingHall.cells(3).get(1);
        // A lectern on the ground just east of a villager west of the door's line (not firm ground for a rail): north
        // then west and west then north are as dear, and only setting out north leaves room behind it, to the south.
        var lectern = new HashMap<Long, BaseSite.Column>();
        lectern.put(key(5, 20), new BaseSite.Column(64, false, 8));
        var route = RailRoute.plan(ground(lectern), 4, 20, HALL, 3, cell).orElseThrow();
        connected(route.rails());
        assertEquals(new Pos(4, 64, 22), route.cart(), "The cart behind it to the south: " + route.rails());
        assertEquals(new Pos(4, 64, 23), route.push());
        // The same from the other side: setting out west, its run-up to the east.
        var south = new HashMap<Long, BaseSite.Column>();
        south.put(key(4, 21), new BaseSite.Column(64, false, 8));
        var west = RailRoute.plan(ground(south), 4, 20, HALL, 3, cell).orElseThrow();
        connected(west.rails());
        assertEquals(new Pos(6, 64, 20), west.cart(), "The cart behind it to the east: " + west.rails());
        assertEquals(new Pos(3, 64, 20), west.rails().get(3), "Out west first");
        // Boxed in on three sides, the fourth toward the hall: no run-up, no line.
        var boxed = new HashMap<Long, BaseSite.Column>();
        for (var at : new int[][] {{5, 20}, {4, 21}, {3, 20}}) boxed.put(key(at[0], at[1]), new BaseSite.Column(64, false, 8));
        assertTrue(RailRoute.plan(ground(boxed), 4, 20, HALL, 3, cell).isEmpty(), "No room behind it any way it could set out");
    }

    @Test
    void aLineNeverComesBackOntoOrBesideItsOwnRailsOrItsRunUp() {
        var cell = TradingHall.cells(3).get(0);
        // The hall's own ground, which no rail is laid on; a villager in its boat by the hall's west front corner, and
        // something in the way to its north where the push would start for the line straight south. Round it, the
        // cheapest line came back through the cart's own rail (suite run 49), joining the run-up into a curve.
        var near = new HashMap<Long, BaseSite.Column>();
        for (int x = 0; x < TradingHall.width(3); x++) for (int z = 0; z < TradingHall.DEPTH; z++) near.put(key(x, z), new BaseSite.Column(68, false, 8));
        near.put(key(-1, 2), new BaseSite.Column(64, false, 8));
        var route = RailRoute.plan(ground(near), -1, 6, HALL, 3, cell, RailRoute.BOAT_ROOM).orElseThrow();
        connected(route.rails());
        var rails = route.rails();
        for (int i = 0; i < rails.size(); i++) {
            for (int j = i + 2; j < rails.size(); j++) {
                var a = rails.get(i);
                var b = rails.get(j);
                assertTrue(Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) > 1, "Rails " + i + " and " + j + " beside each other out of order: " + rails);
            }
        }
        assertTrue(rails.stream().noneMatch(p -> p.x() == route.push().x() && p.z() == route.push().z()), "Nothing laid where the push starts: " + rails);
    }

    @Test
    void aStepIsClimbedOnlyWhereItsLowerColumnHasRoomForTheRiderAtTheSlopesTop() {
        var cell = TradingHall.cells(3).get(1);
        // A block up across the way at z 16 and on to the hall; the lower column before it, at 17, with two open.
        var step = new HashMap<Long, BaseSite.Column>();
        for (int x = -60; x <= 60; x++) for (int z = 9; z <= 16; z++) step.put(key(x, z), new BaseSite.Column(64, true, 8));
        for (int x = -60; x <= 60; x++) step.put(key(x, 8), new BaseSite.Column(63, true, 8));
        for (int x = -60; x <= 60; x++) if (x != 6) step.put(key(x, 17), new BaseSite.Column(63, true, 2));
        var climb = RailRoute.plan(ground(step), 3, 20, HALL, 3, cell).orElseThrow();
        connected(climb.rails());
        // Up the step only at x 6, where the lower column is open over its slope.
        for (int i = 1; i < climb.rails().size(); i++) {
            var a = climb.rails().get(i - 1);
            var b = climb.rails().get(i);
            if (b.y() > a.y() && a.z() == 17) assertEquals(6, a.x(), "Climbed where there is room: " + climb.rails());
        }
    }

    /**
     * For a villager riding in a boat in the cart, only the wall over the door and the cell's lintel are in
     * its way.
     */
    @Test
    void theHallsOnlyBlocksAtARidersEyesInABoatAreTheWallOverTheDoorAndTheCellsLintel() {
        for (int cells : new int[] {1, 3, 4}) {
            var hall = TradingHall.of(cells, "oak", "cobblestone");
            var solid = new java.util.HashSet<Pos>();
            for (var block : hall.blocks()) {
                String id = block.spec().toString();
                if (!id.contains("torch") && !id.contains("door")) solid.add(block.at());
            }
            for (var cell : TradingHall.cells(cells)) {
                var over = new java.util.HashSet<Pos>();
                for (var rail : RailRoute.inside(cells, cell)) {
                    var eyes = new Pos(rail.x(), rail.y() + 2, rail.z());
                    if (solid.contains(eyes)) over.add(eyes);
                    // Its cart's room: the two blocks over each rail are open, the door's taken off.
                    for (int up = 0; up < 2; up++) assertTrue(!solid.contains(new Pos(rail.x(), rail.y() + up, rail.z())), "Open over " + rail);
                }
                assertEquals(new java.util.HashSet<>(RailRoute.overhead(cells, cell)), over, "The blocks taken out for a boat's ride, " + cells + " cells, cell " + cell.index());
            }
        }
    }
}
