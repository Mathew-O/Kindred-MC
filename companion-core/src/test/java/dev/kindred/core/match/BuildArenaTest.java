package dev.kindred.core.match;

import dev.kindred.core.construction.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the build battle's arena: a walkway in front of every plot with a gold mark before each one,
 * a lobby pad at one end, a podium of three steps across the other, and nothing of it on a plot.
 */
class BuildArenaTest {
    private final UUID ada = UUID.randomUUID(), bram = UUID.randomUUID();

    @Test
    void aWalkwayWithAMarkBeforeEachPlotALobbyAndAPodium() {
        var plots = BuildBattle.layout(List.of(ada, bram), new Pos(0, 10, 0));
        var arena = BuildArena.around(plots);
        var walkZ = BuildBattle.PLOT + 1;
        // A gold mark under each viewing spot, in front of the middle of its plot.
        for (var plot : plots) {
            var spot = arena.spots().get(plot.builder());
            assertEquals(plot.origin().x() + BuildBattle.PLOT / 2, spot.x());
            assertEquals(walkZ + 1, spot.z());
            assertEquals("minecraft:gold_block", arena.blocks().get(new Pos(spot.x(), spot.y() - 1, spot.z())));
        }
        // The walkway runs from the lobby past both plots to the podium, railed on its far side.
        assertEquals("minecraft:polished_andesite", arena.blocks().get(new Pos(0, 11, walkZ)));
        assertEquals("minecraft:oak_fence", arena.blocks().get(new Pos(0, 12, walkZ + BuildArena.WALK_WIDTH)));
        assertEquals("minecraft:smooth_quartz", arena.blocks().get(new Pos(arena.lobby().x(), arena.lobby().y() - 1, arena.lobby().z())));
        // Nothing of the arena on a plot.
        for (var at : arena.blocks().keySet()) for (var plot : plots) assertFalse(plot.contains(at), "on a plot: " + at);
        // The podium: first tallest, in the middle; its steps' blocks by place.
        var podium = arena.podium();
        assertEquals(3, podium.size());
        assertTrue(podium.get(0).y() > podium.get(1).y() && podium.get(1).y() > podium.get(2).y());
        assertEquals(podium.get(0).x(), podium.get(1).x());
        var blocks = arena.podiumBlocks(2);
        assertEquals("minecraft:gold_block", blocks.get(new Pos(podium.get(0).x(), podium.get(0).y() - 1, podium.get(0).z())));
        assertEquals("minecraft:iron_block", blocks.get(new Pos(podium.get(1).x(), podium.get(1).y() - 1, podium.get(1).z())));
        assertEquals(5, blocks.size(), "three for first, two for second, none for a third place nobody took");
    }
}
