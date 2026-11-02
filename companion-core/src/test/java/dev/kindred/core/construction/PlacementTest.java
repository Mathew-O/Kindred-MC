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
        assertEquals(Rule.UNSUPPORTED, Placement.rule(BlockSpec.parse("minecraft:oak_sign[rotation=4]")));
        assertEquals(Rule.UNSUPPORTED, Placement.rule(BlockSpec.parse("minecraft:dispenser[facing=up]")), "no rule, no guess");
        assertEquals(Rule.HOPPER, Placement.rule(BlockSpec.parse("minecraft:hopper[facing=down]")));
        assertEquals(Rule.HOPPER, Placement.rule(BlockSpec.parse("minecraft:hopper[facing=west]")));
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
        // A hopper is set against the block it feeds, on that block's face toward it.
        assertEquals(List.of(Face.UP), Placement.faces(BlockSpec.parse("minecraft:hopper[facing=down]")), "on top of what it feeds");
        assertEquals(List.of(Face.EAST), Placement.faces(BlockSpec.parse("minecraft:hopper[facing=west]")), "on the east face of the block west of it");
        assertNull(Placement.playerFacing(BlockSpec.parse("minecraft:hopper[facing=south]")), "the look does not matter");
    }

    @Test
    void aDoorAndABedArePlacedByTheirFirstHalfTheRestComingWithIt() {
        var door = BlockSpec.parse("minecraft:oak_door[facing=south,half=lower,hinge=left,open=false]");
        var bed = BlockSpec.parse("minecraft:red_bed[facing=east,part=foot]");
        assertEquals(Rule.DOOR, Placement.rule(door));
        assertEquals(Rule.COMPANION, Placement.rule(door.with("half", "upper")));
        assertEquals(Rule.BED, Placement.rule(bed));
        assertEquals(Rule.COMPANION, Placement.rule(bed.with("part", "head")));
        assertEquals(Face.SOUTH, Placement.playerFacing(door), "facing the way the player looks");
        assertEquals(List.of(Face.UP), Placement.faces(bed), "on the top of the block below");
        assertEquals(new Pos(0, 1, 0), Placement.partner(door, new Pos(0, 0, 0)).orElseThrow(), "the upper half above");
        assertEquals(new Pos(1, 0, 0), Placement.partner(bed, new Pos(0, 0, 0)).orElseThrow(), "the head ahead of the foot");
        assertTrue(door.sameAs(door.with("hinge", "right")), "the hinge is vanilla's to choose");
        assertEquals(Rule.UNSUPPORTED, Placement.rule(BlockSpec.parse("minecraft:oak_trapdoor[facing=north,half=bottom]")));

        // A room with a door and a bed in it, built whole: the upper half and the head never placed on their own.
        var blocks = new java.util.ArrayList<Blueprint.Block>();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) blocks.add(new Blueprint.Block(new Pos(x, 0, z), BlockSpec.of("minecraft:oak_planks")));
        blocks.add(new Blueprint.Block(new Pos(2, 1, 4), door));
        blocks.add(new Blueprint.Block(new Pos(2, 2, 4), door.with("half", "upper")));
        blocks.add(new Blueprint.Block(new Pos(1, 1, 1), bed));
        blocks.add(new Blueprint.Block(new Pos(2, 1, 1), bed.with("part", "head")));
        var room = new Blueprint("room", "test", "Kindred", "Kindred's own", blocks);
        var bill = BillOfMaterials.of(room.placed(new Pos(0, 1, 0), 0), new BuildSite(BuildTestSites.flat()), java.util.Map.of());
        assertEquals(1, bill.needed().get("minecraft:oak_door"), "one door item for both halves");
        assertEquals(1, bill.needed().get("minecraft:red_bed"));
        var origin = new Pos(-2, 1, -2);
        BuildSimulation.build(room.placed(origin, 0), room.placedClear(origin, 0), BuildTestSites.flat(), new Pos(-6, 1, 0),
                java.util.Set.of("minecraft:oak_planks", "minecraft:oak_door", "minecraft:red_bed", "minecraft:dirt"), "room");
    }
}
