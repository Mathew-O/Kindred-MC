package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Builds with blocks a hut never has, played through the planner as the runner plays them (M-05). */
class PropTest {
    private static final Set<String> HELD = Set.of("minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_log",
            "minecraft:glass", "minecraft:stone_brick_stairs", "minecraft:lantern", "minecraft:dirt");

    /**
     * The kiosk the schematic fixture builds: a floor ring, posts and glass, and a roof of logs lying in two rows with
     * top-half stairs between them, a lantern on top. A lying log is set only against a face along its axis, so the
     * first of each row has nothing to be set against: it is propped, and the prop comes down after.
     */
    static Blueprint kiosk() {
        var blocks = new ArrayList<Blueprint.Block>();
        var clear = new ArrayList<Pos>();
        for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) {
            boolean corner = (x == 0 || x == 2) && (z == 0 || z == 2);
            if (x == 1 && z == 1) { clear.add(new Pos(1, 0, 1)); clear.add(new Pos(1, 1, 1)); continue; }
            blocks.add(new Blueprint.Block(new Pos(x, 0, z), BlockSpec.of(corner ? "minecraft:cobblestone" : "minecraft:oak_planks")));
            if (corner) blocks.add(new Blueprint.Block(new Pos(x, 1, z), BlockSpec.parse("minecraft:oak_log[axis=y]")));
            else if (z == 1) blocks.add(new Blueprint.Block(new Pos(x, 1, z), BlockSpec.of("minecraft:glass")));
            else clear.add(new Pos(x, 1, z));
        }
        for (int x = 0; x <= 2; x++) {
            blocks.add(new Blueprint.Block(new Pos(x, 2, 0), BlockSpec.parse("minecraft:oak_log[axis=x]")));
            blocks.add(new Blueprint.Block(new Pos(x, 2, 2), BlockSpec.parse("minecraft:oak_log[axis=x]")));
            blocks.add(new Blueprint.Block(new Pos(x, 2, 1), BlockSpec.parse("minecraft:stone_brick_stairs[facing=north,half=top,shape=straight]")));
        }
        blocks.add(new Blueprint.Block(new Pos(1, 3, 1), BlockSpec.parse("minecraft:lantern[hanging=false]")));
        return new Blueprint("kiosk", "test", "", "", blocks, clear);
    }

    @Test
    void logsLyingInARowWithNothingAtTheirEndsArePropped() {
        var kiosk = kiosk();
        assertEquals(24, kiosk.blocks().size());
        for (int turns = 0; turns < 4; turns++) {
            var run = BuildSimulation.build(kiosk.placed(new Pos(0, 1, 0), turns), kiosk.placedClear(new Pos(0, 1, 0), turns),
                    BuildTestSites.flat(), new Pos(-5, 1, 6), HELD, "kiosk turned " + turns);
            assertTrue(run.props() > 0, "the first log of a row is propped");
        }
    }

    @Test
    void aBlockWithAFaceToBeSetAgainstIsNotPropped() {
        var blocks = List.of(new Blueprint.Block(new Pos(3, 1, 0), BlockSpec.parse("minecraft:oak_log[axis=x]")),
                new Blueprint.Block(new Pos(4, 1, 0), BlockSpec.parse("minecraft:oak_log[axis=x]")));
        var cells = BuildTestSites.flat();
        cells.put(new Pos(2, 1, 0), BuildTestSites.solid("minecraft:stone"));
        var run = BuildSimulation.build(blocks, List.of(), cells, new Pos(3, 1, 3), HELD, "a row from a wall");
        assertEquals(0, run.props(), "set against the wall at its end");
    }
}
