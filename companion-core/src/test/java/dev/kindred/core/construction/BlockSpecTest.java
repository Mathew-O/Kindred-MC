package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how the player reads and writes a block with its settings, such as which way a stair
 * faces: it reads them as the game writes them, ignores settings that only follow from the neighbouring
 * blocks, and turns them correctly when a build is rotated.
 */
class BlockSpecTest {
    @Test
    void aStateReadsAndWritesAsMinecraftWritesIt() {
        var stairs = BlockSpec.parse("minecraft:oak_stairs[half=bottom,facing=north]");
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", stairs.toString(), "properties kept sorted");
        assertEquals(stairs, BlockSpec.parse(stairs.toString()));
        assertEquals("minecraft:stone", BlockSpec.parse("stone").toString(), "the namespace assumed");
        assertThrows(IllegalArgumentException.class, () -> BlockSpec.parse("minecraft:oak_stairs[facing"));
    }

    @Test
    void neighboursAndPlayDoNotMakeADifferentBlock() {
        var fence = BlockSpec.parse("minecraft:oak_fence[east=true,north=false,south=false,west=false,waterlogged=false]");
        assertTrue(fence.sameAs(BlockSpec.of("minecraft:oak_fence")), "a fence's connections follow from its neighbours");
        var north = BlockSpec.parse("minecraft:oak_stairs[facing=north,half=bottom,shape=straight]");
        assertTrue(north.sameAs(BlockSpec.parse("minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left]")));
        assertFalse(north.sameAs(BlockSpec.parse("minecraft:oak_stairs[facing=south,half=bottom]")), "a stair turned the other way is not it");
    }

    @Test
    void aTurnTurnsItsFacingAxisRotationAndSides() {
        var stairs = BlockSpec.parse("minecraft:oak_stairs[facing=north,half=top]");
        assertEquals("east", stairs.rotated(1).property("facing"));
        assertEquals("west", stairs.rotated(-1).property("facing"));
        assertEquals("top", stairs.rotated(2).property("half"));
        assertEquals("z", BlockSpec.parse("minecraft:oak_log[axis=x]").rotated(1).property("axis"));
        assertEquals("y", BlockSpec.parse("minecraft:oak_log[axis=y]").rotated(1).property("axis"));
        assertEquals("6", BlockSpec.parse("minecraft:oak_sign[rotation=2]").rotated(1).property("rotation"));
        var fence = BlockSpec.parse("minecraft:oak_fence[north=true,east=false,south=false,west=false]").rotated(1);
        assertEquals(List.of("false", "true"), List.of(fence.property("north"), fence.property("east")));
    }
}
