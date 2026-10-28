package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
