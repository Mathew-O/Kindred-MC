package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintTest {
    @Test
    void placedTurnedItsBlocksAndTheirStatesGoRoundTogether() {
        var print = new Blueprint("step", "test", "", "", List.of(
                new Blueprint.Block(new Pos(1, 0, 0), BlockSpec.parse("minecraft:oak_stairs[facing=east,half=bottom]"))));
        var turned = print.placed(new Pos(10, 64, 10), 1).getFirst();
        assertEquals(new Pos(10, 64, 11), turned.at(), "east of the origin turns to its south");
        assertEquals("south", turned.spec().property("facing"), "and the stair faces south, as it did east");
        assertEquals(new Pos(11, 64, 10), print.placed(new Pos(10, 64, 10), 4).getFirst().at(), "four turns are none");
    }

    @Test
    void aBlueprintIsBoundedAndHasNoAirBlocksOrDoubles() {
        assertThrows(IllegalArgumentException.class, () -> new Blueprint.Block(new Pos(0, 0, 0), BlockSpec.of("minecraft:air")));
        var stone = BlockSpec.of("minecraft:stone");
        assertThrows(IllegalArgumentException.class, () -> new Blueprint("two", "", "", "",
                List.of(new Blueprint.Block(new Pos(0, 0, 0), stone), new Blueprint.Block(new Pos(0, 0, 0), stone))));
        assertThrows(IllegalArgumentException.class, () -> new Blueprint("wide", "", "", "",
                List.of(new Blueprint.Block(new Pos(0, 0, 0), stone), new Blueprint.Block(new Pos(200, 0, 0), stone))));
    }
}
