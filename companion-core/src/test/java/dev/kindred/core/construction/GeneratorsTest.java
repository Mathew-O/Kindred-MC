package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorsTest {
    private static final Set<String> HELD = Set.of("minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_stairs",
            "minecraft:oak_slab", "minecraft:oak_log", "minecraft:torch", "minecraft:dirt");

    @Test
    void aHutHasAFloorWallsADoorwayATorchAndARoof() {
        var hut = Generators.hut(5, 5, 3);
        var at = Blueprint.byPosition(hut.blocks());
        assertEquals("minecraft:oak_planks", at.get(new Pos(2, 0, 2)).block());
        assertEquals("y", at.get(new Pos(0, 2, 0)).property("axis"), "log corners stand upright");
        assertEquals("minecraft:cobblestone", at.get(new Pos(2, 3, 4)).block(), "a lintel over the doorway");
        assertNull(at.get(new Pos(2, 1, 4)), "the doorway");
        assertTrue(hut.clear().contains(new Pos(2, 2, 4)) && hut.clear().contains(new Pos(2, 1, 2)), "kept clear: doorway and inside");
        assertEquals("south", at.get(new Pos(2, 2, 1)).property("facing"), "a torch on the north wall");
        assertEquals("south", at.get(new Pos(0, 4, 0)).property("facing"), "north eaves face in");
        assertEquals("north", at.get(new Pos(0, 4, 4)).property("facing"), "south eaves face in");
        assertEquals("minecraft:oak_slab", at.get(new Pos(3, 5, 2)).block(), "an odd span ridged with slabs beside the last stairs");
        var wide = Blueprint.byPosition(Generators.hut(5, 9, 3).blocks());
        assertEquals("minecraft:oak_planks", wide.get(new Pos(1, 5, 2)).block(), "a course of stairs rests on planks");
        assertEquals("south", wide.get(new Pos(1, 6, 2)).property("facing"));
        assertEquals("kindred:generator/hut?width=5&depth=5&walls=3", hut.source());
        assertThrows(IllegalArgumentException.class, () -> Generators.hut(2, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> Generators.named("castle", List.of()));
        assertEquals(Generators.hut(7, 5, 3), Generators.named("hut", List.of(7, 5, 3)));
    }

    /**
     * The planner builds a whole hut on flat ground, step by step, as the runner would (see {@link BuildSimulation}):
     * every block ends in its state, nothing is left in the clear space, and the scaffold comes down.
     */
    @Test
    void thePlannerBuildsAWholeHutAndTakesItsScaffoldDown() {
        for (int[] size : new int[][] {{5, 5, 3}, {7, 9, 4}, {3, 4, 2}}) for (int turns = 0; turns < 4; turns++) {
            var hut = Generators.hut(size[0], size[1], size[2]);
            var origin = new Pos(-size[0] / 2, 1, -size[1] / 2);
            var run = BuildSimulation.build(hut.placed(origin, turns), hut.placedClear(origin, turns), BuildTestSites.flat(),
                    new Pos(-6, 1, 0), HELD, hut.name() + " turned " + turns);
            assertTrue(run.scaffolds() > 0, "the ridge is out of reach from the ground");
        }
    }
}
