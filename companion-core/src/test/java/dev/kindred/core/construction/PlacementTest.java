package dev.kindred.core.construction;

import dev.kindred.core.construction.Placement.Rule;
import dev.kindred.core.construction.Pos.Face;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlacementTest {
    @Test
    void eachKindOfBlockHasItsRule() {
        assertEquals(Rule.FULL, Placement.rule(BlockSpec.of("minecraft:cobblestone")));
        assertEquals(Rule.FULL, Placement.rule(BlockSpec.parse("minecraft:glass_pane[east=true]")));
        assertEquals(Rule.STAIRS, Placement.rule(BlockSpec.parse("minecraft:oak_stairs[facing=north,half=bottom]")));
        assertEquals(Rule.SLAB, Placement.rule(BlockSpec.parse("minecraft:oak_slab[type=top]")));
        assertEquals(Rule.UNSUPPORTED, Placement.rule(BlockSpec.parse("minecraft:oak_slab[type=double]")), "two slabs in one cell: not yet");
        assertEquals(Rule.AXIS, Placement.rule(BlockSpec.parse("minecraft:oak_log[axis=x]")));
        assertEquals(Rule.FACING_PLAYER, Placement.rule(BlockSpec.parse("minecraft:furnace[facing=north]")));
        assertEquals(Rule.WALL, Placement.rule(BlockSpec.parse("minecraft:wall_torch[facing=east]")));
        assertEquals(Rule.GRAVITY, Placement.rule(BlockSpec.of("minecraft:sand")));
        assertEquals(Rule.UNSUPPORTED, Placement.rule(BlockSpec.parse("minecraft:oak_door[facing=north,half=lower,hinge=left]")));
        assertEquals(Rule.UNSUPPORTED, Placement.rule(BlockSpec.parse("minecraft:dispenser[facing=up]")), "no rule, no guess");
    }

    @Test
    void theFacesAndTheLookThatGiveEachState() {
        assertEquals(List.of(Face.EAST, Face.WEST), Placement.faces(BlockSpec.parse("minecraft:oak_log[axis=x]")));
        assertFalse(Placement.faces(BlockSpec.parse("minecraft:oak_slab[type=top]")).contains(Face.UP), "a top slab is never set on a block below");
        assertEquals(List.of(Face.EAST), Placement.faces(BlockSpec.parse("minecraft:wall_torch[facing=east]")), "hung on the face it points from");
        assertEquals(Face.NORTH, Placement.playerFacing(BlockSpec.parse("minecraft:oak_stairs[facing=north,half=bottom]")));
        assertEquals(Face.SOUTH, Placement.playerFacing(BlockSpec.parse("minecraft:furnace[facing=north]")), "a furnace faces back at the player");
        assertEquals(0.75, Placement.hitHeight(BlockSpec.parse("minecraft:oak_stairs[facing=north,half=top]")));
        assertEquals(Face.SOUTH, Placement.facingOf(0));
        assertEquals(Face.WEST, Placement.facingOf(90));
        assertEquals(Face.NORTH, Placement.facingOf(180));
        assertEquals(Face.EAST, Placement.facingOf(-90));
        assertEquals("minecraft:torch", Placement.item(BlockSpec.parse("minecraft:wall_torch[facing=east]")));
    }
}
