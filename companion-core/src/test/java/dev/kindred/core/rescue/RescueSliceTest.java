package dev.kindred.core.rescue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RescueSliceTest {
    private static final RescueSlice.Cell STONE = RescueSlice.Cell.solid("stone", true);
    private static final RescueSlice.Cell OBSIDIAN = RescueSlice.Cell.solid("obsidian", false);

    /** A staged scene: open above y 0, stone from y -1 down, with cells set by hand over that. */
    private static final class Scene implements RescueSlice.Reader {
        final Map<List<Integer>, RescueSlice.Cell> set = new HashMap<>();
        RescueSlice.Cell ground = STONE;

        Scene put(int dx, int dy, int dz, RescueSlice.Cell cell) {
            set.put(List.of(dx, dy, dz), cell);
            return this;
        }

        @Override
        public RescueSlice.Cell at(int dx, int dy, int dz) {
            var cell = set.get(List.of(dx, dy, dz));
            if (cell != null) return cell;
            return dy < 0 ? ground : RescueSlice.Cell.OPEN;
        }
    }

    /** The pit: a shaft one wide and three deep in obsidian, open sky over it. */
    private static Scene pit() {
        var scene = new Scene();
        scene.ground = OBSIDIAN;
        for (int dx = -RescueSlice.RADIUS; dx <= RescueSlice.RADIUS; dx++)
            for (int dz = -RescueSlice.RADIUS; dz <= RescueSlice.RADIUS; dz++)
                for (int dy = 0; dy <= 2; dy++) if (dx != 0 || dz != 0) scene.put(dx, dy, dz, OBSIDIAN);
        return scene;
    }

    @Test
    void inThePitEveryWayIsAWallItCannotBreakAndUpIsOpen() {
        var slice = RescueSlice.perceive(pit());
        for (var reach : slice.ways(8).values()) {
            assertEquals(0, reach.went());
            assertEquals("a two-high wall of obsidian", reach.stop());
        }
        var up = slice.up(6);
        assertEquals(6, up.went());
        assertEquals(List.of("obsidian"), slice.unbreakableWithin(4.5));
        String text = slice.waysText(8);
        assertTrue(text.contains("- north: not a block, then a two-high wall of obsidian"), text);
        assertTrue(text.contains("- up: 6 blocks clear over its head"), text);
        assertTrue(text.contains("- down: obsidian (cannot break)"), text);
    }

    @Test
    void whatIsBehindAWallItCannotSeeIsUnknownNotTheBlockThere() {
        var scene = pit().put(2, 0, 0, RescueSlice.Cell.solid("diamond_ore", true));
        var slice = RescueSlice.perceive(scene);
        assertEquals("obsidian", slice.at(1, 0, 0).id());
        assertEquals(RescueSlice.Kind.UNKNOWN, slice.at(2, 0, 0).kind());
        var key = new LinkedHashMap<String, Character>();
        String grid = slice.grid(key);
        assertFalse(key.containsKey("diamond_ore"), "The ore behind the wall is not in the key: " + key);
        assertTrue(grid.contains("?"), grid);
        // The top of the pit's rim is seen from the shaft's open air above it.
        assertEquals("obsidian", slice.at(2, 2, 0).id());
        // Read as it is, unmasked, the ore would be there: the mask is what hides it.
        assertEquals("diamond_ore", RescueSlice.of(scene).at(2, 0, 0).id());
    }

    @Test
    void aWalkGoesLevelUpAStepAndStopsAtATwoHighWallSayingHowFarItWent() {
        var scene = new Scene()
                .put(0, 0, -3, STONE)              // a step up
                .put(0, 0, -4, STONE)              // the floor beyond it, a block higher
                .put(0, 1, -5, STONE).put(0, 2, -5, STONE);
        var reach = RescueSlice.perceive(scene).walk(RescuePlan.Way.NORTH, 8);
        assertEquals(4, reach.went());
        assertEquals(1, reach.rise());
        assertEquals("a two-high wall of stone", reach.stop());
        assertEquals("4 blocks, up 1, then a two-high wall of stone", reach.text());
    }

    @Test
    void aWalkStepsDownADropOfOneAndStopsAtADropOfTwo() {
        var scene = new Scene().put(0, -1, 1, RescueSlice.Cell.OPEN)  // a hole one deep at the first block south
                .put(0, -1, 2, RescueSlice.Cell.OPEN).put(0, -2, 2, RescueSlice.Cell.OPEN)
                .put(0, -3, 2, RescueSlice.Cell.OPEN);
        var reach = RescueSlice.perceive(scene).walk(RescuePlan.Way.SOUTH, 8);
        assertEquals(1, reach.went());
        assertEquals(-1, reach.rise());
        assertEquals("a drop of 2", reach.stop());
    }

    @Test
    void aDropTooDeepStopsTheWalkAtItsEdge() {
        var scene = new Scene();
        for (int dy = -1; dy >= -3; dy--) scene.put(1, dy, 0, RescueSlice.Cell.OPEN);
        var reach = RescueSlice.perceive(scene).walk(RescuePlan.Way.EAST, 8);
        assertEquals(0, reach.went());
        assertEquals("a drop of 3", reach.stop());
    }

    @Test
    void waterAndLavaStopAWalk() {
        var wet = new Scene().put(-2, 0, 0, RescueSlice.Cell.water());
        assertEquals("1 block, then water", RescueSlice.perceive(wet).walk(RescuePlan.Way.WEST, 8).text());
        var hot = new Scene().put(-1, -1, 0, RescueSlice.Cell.OPEN).put(-1, -2, 0, RescueSlice.Cell.lava());
        assertEquals("a drop of 1 into lava", RescueSlice.perceive(hot).walk(RescuePlan.Way.WEST, 8).stop());
    }

    @Test
    void aStepWithNoRoomToJumpStopsTheWalk() {
        var scene = new Scene().put(0, 0, -1, STONE);
        for (int dx = -RescueSlice.RADIUS; dx <= RescueSlice.RADIUS; dx++)
            for (int dz = -RescueSlice.RADIUS; dz <= RescueSlice.RADIUS; dz++) scene.put(dx, 2, dz, STONE);
        var reach = RescueSlice.perceive(scene).walk(RescuePlan.Way.NORTH, 8);
        assertEquals(0, reach.went());
        assertEquals("a step of stone with no room to jump (stone overhead)", reach.stop());
        assertEquals("stone", RescueSlice.perceive(scene).up(6).stop());
    }

    @Test
    void theBlocksNearItItCannotBreakAreNamedNearestFirstAndOnlyThoseItSees() {
        var scene = new Scene().put(1, 0, 0, RescueSlice.Cell.solid("cobbled_deepslate", false))
                .put(0, 0, 3, OBSIDIAN)
                .put(0, -1, 0, RescueSlice.Cell.solid("deepslate", false));
        var slice = RescueSlice.perceive(scene);
        assertEquals(List.of("deepslate", "cobbled_deepslate", "obsidian"), slice.unbreakableWithin(4.5));
        assertTrue(slice.unbreakableWithin(2.5).containsAll(List.of("deepslate", "cobbled_deepslate")));
        assertFalse(slice.unbreakableWithin(2.5).contains("obsidian"));
    }

    @Test
    void theHardestBlockItsPickaxeHarvestsFollowsTheTier() {
        assertTrue(RescueSlice.harvests(0).contains("no stone"));
        assertTrue(RescueSlice.harvests(1).contains("deepslate"));
        assertTrue(RescueSlice.harvests(2).contains("iron"));
        assertTrue(RescueSlice.harvests(3).contains("but obsidian"));
        assertEquals("everything but bedrock", RescueSlice.harvests(4));
    }
}
