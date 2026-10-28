package dev.kindred.core.construction;

import dev.kindred.core.construction.BuildSite.Cell;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BunkersTest {
    /** Deep ground: stone from twelve down to the top at y 0, air above. */
    private static Map<Pos, Cell> deep() {
        var cells = new HashMap<Pos, Cell>();
        for (int x = -12; x <= 12; x++) for (int z = -8; z <= 22; z++) {
            for (int y = -12; y <= 0; y++) cells.put(new Pos(x, y, z), BuildTestSites.STONE);
            for (int y = 1; y <= 12; y++) cells.put(new Pos(x, y, z), Cell.AIR);
        }
        return cells;
    }

    @Test
    void theHideoutIsALinedRoomBelowAShedReachedByAStair() {
        var hideout = Bunkers.hideout(BuildStyle.CLASSIC);
        var at = Blueprint.byPosition(hideout.blocks());
        var clear = new HashSet<>(hideout.clear());
        for (var b : hideout.blocks()) assertNotEquals(Placement.Rule.UNSUPPORTED, Placement.rule(b.spec()), "placeable: " + b.spec());
        // The shed's door, its north wall; the floor inside it.
        assertEquals("minecraft:oak_door", at.get(new Pos(1, 0, 0)).block());
        assertEquals("minecraft:cobblestone", at.get(new Pos(1, -1, 1)).block());
        // The stair: a step down a row, each with a floor under it and three high clear above, from the shed to the room.
        for (int z = Bunkers.STAIR_TOP + 1; z <= Bunkers.STAIR_FOOT; z++) {
            int feet = -Math.min(Bunkers.DEPTH, z - Bunkers.STAIR_TOP);
            assertTrue(at.containsKey(new Pos(1, feet - 1, z)), "a step at row " + z);
            for (int y = feet; y <= feet + 2; y++) assertTrue(clear.contains(new Pos(1, y, z)) || at.get(new Pos(1, y, z)).block().endsWith("torch"), "head room at " + z + "," + y);
        }
        // The room: seven by seven and three high, clear but for what it holds, lined all round.
        int feet = -Bunkers.DEPTH, held = 0;
        for (int x = Bunkers.ROOM_WEST; x <= Bunkers.ROOM_EAST; x++) for (int z = Bunkers.ROOM_NORTH; z <= Bunkers.ROOM_SOUTH; z++) {
            assertEquals("minecraft:cobblestone", at.get(new Pos(x, feet - 1, z)).block(), "floor");
            assertEquals("minecraft:cobblestone", at.get(new Pos(x, feet + Bunkers.ROOM_HEIGHT, z)).block(), "ceiling");
            for (int y = feet; y < feet + Bunkers.ROOM_HEIGHT; y++) {
                var p = new Pos(x, y, z);
                if (at.containsKey(p)) held++;
                else assertTrue(clear.contains(p), "room clear at " + p);
            }
        }
        assertEquals(6, held, "a table, a furnace, two chests and two torches");
        for (int z = Bunkers.ROOM_NORTH; z <= Bunkers.ROOM_SOUTH; z++) {
            assertEquals("minecraft:oak_planks", at.get(new Pos(Bunkers.ROOM_WEST - 1, feet + 1, z)).block(), "west wall lined");
            assertEquals("minecraft:oak_planks", at.get(new Pos(Bunkers.ROOM_EAST + 1, feet + 1, z)).block(), "east wall lined");
        }
        assertTrue(clear.contains(new Pos(1, feet, Bunkers.ROOM_NORTH - 1)), "its way in from the stair");
        assertEquals(hideout, Generators.named("bunker", List.of()));
        // Its bill: no more than a player gathers early on.
        var bill = BillOfMaterials.of(hideout.blocks(), new BuildSite(deep()), Map.of());
        assertTrue(bill.needed().get("minecraft:cobblestone") > 150, "the stone it lines with: " + bill.needed());
        assertEquals(2, bill.needed().get("minecraft:chest"));
    }

    @Test
    void thePlannerDigsItOutAndLinesItFromTheDoorDown() {
        var hideout = Bunkers.hideout(BuildStyle.CLASSIC);
        var origin = new Pos(0, 1, 0);
        var held = new HashSet<>(List.of("minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_door", "minecraft:wall_torch",
                "minecraft:torch", "minecraft:crafting_table", "minecraft:furnace", "minecraft:chest", "minecraft:dirt"));
        var run = BuildSimulation.build(hideout.placed(origin, 0), hideout.placedClear(origin, 0), deep(), new Pos(1, 1, -3), held, hideout.name());
        assertTrue(run.steps() > 300, "dug and lined: " + run.steps() + " steps");
    }
}
