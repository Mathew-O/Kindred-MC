package dev.kindred.core.progression;

import dev.kindred.core.InventorySummary;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class MilestoneGraphTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void emptyInventoryStartsAtGatherWood() {
        MilestoneGraph graph = MilestoneGraph.canonical();
        InventorySummary inv = InventorySummary.empty(36);
        ProgressionObservation obs = new ProgressionObservation(
                id, 1L, inv, false, false, false, false, false, false, true, true
        );

        ProgressionState state = graph.evaluate(obs, Map.of());

        assertFalse(state.isAchieved(MilestoneId.GATHER_WOOD));
        assertTrue(state.activeFrontier().contains(MilestoneId.GATHER_WOOD));
        assertEquals(MilestoneStatus.LOCKED, state.snapshot(MilestoneId.CRAFT_PLANKS).orElseThrow().status());
        assertEquals(MilestoneStatus.LOCKED, state.snapshot(MilestoneId.CRAFT_WOODEN_PICKAXE).orElseThrow().status());
    }

    @Test
    void gatheringWoodUnlocksPlanksAndShelter() {
        MilestoneGraph graph = MilestoneGraph.canonical();
        InventorySummary inv = new InventorySummary(1L, 36, 1, 4, Map.of("minecraft:oak_log", 4));
        ProgressionObservation obs = new ProgressionObservation(
                id, 20L, inv, false, false, false, false, false, false, true, true
        );

        ProgressionState state = graph.evaluate(obs, Map.of());

        assertTrue(state.isAchieved(MilestoneId.GATHER_WOOD));
        assertEquals(MilestoneStatus.UNLOCKED, state.snapshot(MilestoneId.CRAFT_PLANKS).orElseThrow().status());
        assertTrue(state.activeFrontier().contains(MilestoneId.CRAFT_PLANKS));
        assertTrue(state.activeFrontier().contains(MilestoneId.SECURE_SHELTER));
    }

    @Test
    void fullCraftingTierProgressionUnlocksIronAge() {
        MilestoneGraph graph = MilestoneGraph.canonical();

        // Observation with crafting table placed, furnace placed, sticks, iron ingots, planks
        InventorySummary inv = new InventorySummary(5L, 36, 5, 20, Map.of(
                "minecraft:stick", 4,
                "minecraft:iron_ingot", 5,
                "minecraft:oak_planks", 8,
                "minecraft:stone_pickaxe", 1,
                "minecraft:cobblestone", 10
        ));
        ProgressionObservation obs = new ProgressionObservation(
                id, 200L, inv, true, true, false, false, false, false, true, true
        );

        // Prepopulate history up to furnace deployment
        Map<MilestoneId, Long> history = new HashMap<>();
        history.put(MilestoneId.GATHER_WOOD, 10L);
        history.put(MilestoneId.CRAFT_PLANKS, 20L);
        history.put(MilestoneId.CRAFT_STICKS, 30L);
        history.put(MilestoneId.CRAFT_CRAFTING_TABLE, 40L);
        history.put(MilestoneId.DEPLOY_CRAFTING_TABLE, 50L);
        history.put(MilestoneId.CRAFT_WOODEN_PICKAXE, 60L);
        history.put(MilestoneId.MINE_COBBLESTONE, 70L);
        history.put(MilestoneId.CRAFT_STONE_PICKAXE, 80L);
        history.put(MilestoneId.CRAFT_FURNACE, 90L);
        history.put(MilestoneId.DEPLOY_FURNACE, 100L);
        history.put(MilestoneId.SMELT_RESOURCES, 110L);

        ProgressionState state = graph.evaluate(obs, history);

        assertTrue(state.isAchieved(MilestoneId.SMELT_RESOURCES));
        assertTrue(state.activeFrontier().contains(MilestoneId.CRAFT_IRON_PICKAXE));
        assertTrue(state.activeFrontier().contains(MilestoneId.CRAFT_SHIELD));
        assertTrue(state.activeFrontier().contains(MilestoneId.CRAFT_IRON_SWORD));
    }

    @Test
    void lostGearCreatesRecoveryWorkWithoutErasingHistory() {
        MilestoneGraph graph = MilestoneGraph.canonical();

        // Player previously earned a wooden pickaxe at tick 100, but now inventory is empty of pickaxes
        InventorySummary inv = new InventorySummary(2L, 36, 1, 2, Map.of("minecraft:cobblestone", 2));
        ProgressionObservation obs = new ProgressionObservation(
                id, 300L, inv, true, false, false, false, false, false, true, true
        );

        Map<MilestoneId, Long> history = Map.of(
                MilestoneId.GATHER_WOOD, 10L,
                MilestoneId.CRAFT_PLANKS, 20L,
                MilestoneId.CRAFT_STICKS, 30L,
                MilestoneId.CRAFT_CRAFTING_TABLE, 40L,
                MilestoneId.DEPLOY_CRAFTING_TABLE, 50L,
                MilestoneId.CRAFT_WOODEN_PICKAXE, 100L
        );

        ProgressionState state = graph.evaluate(obs, history);

        var pickaxeSnap = state.snapshot(MilestoneId.CRAFT_WOODEN_PICKAXE).orElseThrow();
        assertEquals(MilestoneStatus.UNLOCKED, pickaxeSnap.status());
        assertFalse(pickaxeSnap.physicalPostconditionSatisfied(), "Physical pickaxe was broken/lost");
        assertTrue(pickaxeSnap.historicallyAchieved(), "Achievement history is preserved");
        assertTrue(pickaxeSnap.needsRecovery(), "Flags that recovery work is needed");
        assertTrue(state.recoveryFrontier().contains(MilestoneId.CRAFT_WOODEN_PICKAXE), "Enters recovery frontier");
        assertEquals(100L, pickaxeSnap.firstAchievedTick());
    }

    @Test
    void cyclicGraphThrowsIllegalStateException() {
        Map<MilestoneId, Milestone> cycle = Map.of(
                MilestoneId.GATHER_WOOD, new Milestone(MilestoneId.GATHER_WOOD, "Wood", Set.of(MilestoneId.CRAFT_PLANKS), obs -> true, 1.0),
                MilestoneId.CRAFT_PLANKS, new Milestone(MilestoneId.CRAFT_PLANKS, "Planks", Set.of(MilestoneId.GATHER_WOOD), obs -> true, 2.0)
        );
        assertThrows(IllegalStateException.class, () -> new MilestoneGraph(cycle));
    }

    @Test
    void missingPrerequisiteThrowsIllegalStateException() {
        Map<MilestoneId, Milestone> invalid = Map.of(
                MilestoneId.CRAFT_PLANKS, new Milestone(MilestoneId.CRAFT_PLANKS, "Planks", Set.of(MilestoneId.GATHER_WOOD), obs -> true, 2.0)
        );
        assertThrows(IllegalStateException.class, () -> new MilestoneGraph(invalid));
    }
}
