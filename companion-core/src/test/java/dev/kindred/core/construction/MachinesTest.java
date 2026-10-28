package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MachinesTest {
    @Test
    void theSmelterIsAFurnaceFedByHoppersFromItsChests() {
        var smelter = Machines.smelter();
        var at = Blueprint.byPosition(smelter.blocks());
        assertEquals(7, smelter.blocks().size());
        assertEquals("minecraft:furnace", at.get(new Pos(0, 1, 0)).block());
        // Each hopper feeds the furnace or the output chest, and each chest sits on the hopper it fills, or at its foot.
        assertEquals("down", at.get(new Pos(0, 2, 0)).property("facing"), "what is smelted, into the furnace's top");
        assertEquals("west", at.get(new Pos(1, 1, 0)).property("facing"), "the fuel, into its side");
        assertEquals("south", at.get(new Pos(0, 0, 0)).property("facing"), "what it made, out into the output chest");
        for (var chest : List.of(Machines.INPUT, Machines.FUEL, Machines.OUTPUT)) assertEquals("minecraft:chest", at.get(chest).block());
        for (var b : smelter.blocks()) assertNotEquals(Placement.Rule.UNSUPPORTED, Placement.rule(b.spec()), "placeable: " + b.spec());
        // Its bill: three hoppers of fifteen iron and three chests, three chests more, and the furnace.
        var bill = BillOfMaterials.of(smelter.blocks(), new BuildSite(BuildTestSites.flat()), Map.of());
        assertEquals(3, bill.needed().get("minecraft:hopper"));
        assertEquals(3, bill.needed().get("minecraft:chest"));
        assertEquals(1, bill.needed().get("minecraft:furnace"));
        var hopper = BuildNeeds.recipe("minecraft:hopper", "oak").orElseThrow();
        assertEquals(Map.of("minecraft:iron_ingot", 5, "minecraft:chest", 1), hopper.takes());
        assertTrue(hopper.table());
        assertEquals(smelter, Generators.named("smelter", List.of()));
    }

    @Test
    void thePlannerBuildsTheSmelterEachPartAgainstWhatItFeeds() {
        var smelter = Machines.smelter();
        var origin = new Pos(0, 1, 0);
        var held = new HashSet<>(List.of("minecraft:hopper", "minecraft:chest", "minecraft:furnace", "minecraft:cobblestone", "minecraft:dirt"));
        BuildSimulation.build(smelter.placed(origin, 0), smelter.placedClear(origin, 0), BuildTestSites.flat(), new Pos(0, 1, 4), held, smelter.name());
    }
}
