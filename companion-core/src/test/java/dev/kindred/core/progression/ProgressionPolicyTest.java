package dev.kindred.core.progression;

import dev.kindred.core.InventorySummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how a player picks its next step towards beating the game.
 */
public class ProgressionPolicyTest {

    private final UUID id = UUID.randomUUID();
    private final MilestoneGraph graph = MilestoneGraph.canonical();

    @Test
    void recommendsPlanksWhenLogsAvailable() {
        InventorySummary inv = new InventorySummary(1L, 36, 1, 3, Map.of("minecraft:oak_log", 3));
        ProgressionObservation obs = new ProgressionObservation(
                id, 10L, inv, false, false, false, false, false, false, true, true
        );
        ProgressionState state = graph.evaluate(obs, Map.of(MilestoneId.GATHER_WOOD, 5L));

        List<ProgressionRecommendation> recs = ProgressionPolicy.recommend(state, obs);

        assertFalse(recs.isEmpty());
        var rec = recs.stream().filter(r -> r.milestoneId() == MilestoneId.CRAFT_PLANKS).findFirst().orElseThrow();
        assertEquals("native_craft", rec.skill());
        assertEquals("oak_planks", rec.arguments().get("recipe"));
    }

    @Test
    void recommendsCraftingTableWhenPlanksAvailable() {
        InventorySummary inv = new InventorySummary(1L, 36, 1, 4, Map.of("minecraft:oak_planks", 4));
        ProgressionObservation obs = new ProgressionObservation(
                id, 20L, inv, false, false, false, false, false, false, true, true
        );
        ProgressionState state = graph.evaluate(obs, Map.of(
                MilestoneId.GATHER_WOOD, 5L,
                MilestoneId.CRAFT_PLANKS, 10L
        ));

        List<ProgressionRecommendation> recs = ProgressionPolicy.recommend(state, obs);

        var rec = recs.stream().filter(r -> r.milestoneId() == MilestoneId.CRAFT_CRAFTING_TABLE).findFirst().orElseThrow();
        assertEquals("native_craft", rec.skill());
        assertEquals("crafting_table", rec.arguments().get("recipe"));
    }

    @Test
    void recommendsDeployingCraftingTableWhenInInventory() {
        InventorySummary inv = new InventorySummary(1L, 36, 1, 1, Map.of("minecraft:crafting_table", 1));
        ProgressionObservation obs = new ProgressionObservation(
                id, 30L, inv, false, false, false, false, false, false, true, true
        );
        ProgressionState state = graph.evaluate(obs, Map.of(
                MilestoneId.GATHER_WOOD, 5L,
                MilestoneId.CRAFT_PLANKS, 10L,
                MilestoneId.CRAFT_CRAFTING_TABLE, 20L
        ));

        List<ProgressionRecommendation> recs = ProgressionPolicy.recommend(state, obs);

        var rec = recs.stream().filter(r -> r.milestoneId() == MilestoneId.DEPLOY_CRAFTING_TABLE).findFirst().orElseThrow();
        assertEquals("native_place", rec.skill());
        assertEquals("minecraft:crafting_table", rec.arguments().get("block"));
    }

    @Test
    void recommendsIronPickaxeWhenIronAndSticksAvailableAtTable() {
        InventorySummary inv = new InventorySummary(1L, 36, 2, 5, Map.of(
                "minecraft:iron_ingot", 3,
                "minecraft:stick", 2
        ));
        ProgressionObservation obs = new ProgressionObservation(
                id, 50L, inv, true, true, false, false, false, false, true, true
        );
        Map<MilestoneId, Long> history = new java.util.HashMap<>();
        history.put(MilestoneId.GATHER_WOOD, 5L);
        history.put(MilestoneId.CRAFT_PLANKS, 10L);
        history.put(MilestoneId.CRAFT_STICKS, 15L);
        history.put(MilestoneId.CRAFT_CRAFTING_TABLE, 20L);
        history.put(MilestoneId.DEPLOY_CRAFTING_TABLE, 25L);
        history.put(MilestoneId.CRAFT_WOODEN_PICKAXE, 30L);
        history.put(MilestoneId.MINE_COBBLESTONE, 35L);
        history.put(MilestoneId.CRAFT_STONE_PICKAXE, 40L);
        history.put(MilestoneId.CRAFT_FURNACE, 45L);
        history.put(MilestoneId.DEPLOY_FURNACE, 48L);
        history.put(MilestoneId.SMELT_RESOURCES, 50L);
        ProgressionState state = graph.evaluate(obs, history);

        List<ProgressionRecommendation> recs = ProgressionPolicy.recommend(state, obs);

        var rec = recs.stream().filter(r -> r.milestoneId() == MilestoneId.CRAFT_IRON_PICKAXE).findFirst().orElseThrow();
        assertEquals("native_craft", rec.skill());
        assertEquals("iron_pickaxe", rec.arguments().get("recipe"));
        assertEquals(63.0, rec.need());
    }

    @Test
    void recoveryFrontierReceivesPriorityNeedBoost() {
        // Wooden pickaxe was previously crafted, but now lost
        InventorySummary inv = new InventorySummary(1L, 36, 2, 6, Map.of(
                "minecraft:oak_planks", 4,
                "minecraft:stick", 2
        ));
        ProgressionObservation obs = new ProgressionObservation(
                id, 70L, inv, true, false, false, false, false, false, true, true
        );
        ProgressionState state = graph.evaluate(obs, Map.of(
                MilestoneId.GATHER_WOOD, 5L,
                MilestoneId.CRAFT_PLANKS, 10L,
                MilestoneId.CRAFT_STICKS, 15L,
                MilestoneId.CRAFT_CRAFTING_TABLE, 20L,
                MilestoneId.DEPLOY_CRAFTING_TABLE, 25L,
                MilestoneId.CRAFT_WOODEN_PICKAXE, 30L // Previously achieved!
        ));

        assertTrue(state.recoveryFrontier().contains(MilestoneId.CRAFT_WOODEN_PICKAXE));

        List<ProgressionRecommendation> recs = ProgressionPolicy.recommend(state, obs);

        var rec = recs.stream().filter(r -> r.milestoneId() == MilestoneId.CRAFT_WOODEN_PICKAXE).findFirst().orElseThrow();
        assertEquals(67.5, rec.need(), "Base 62.5 + 5.0 recovery bonus = 67.5");
    }

    @Test
    void emergencyNightShelterRecommendedAtNightWithoutShelter() {
        InventorySummary inv = new InventorySummary(1L, 36, 1, 10, Map.of("minecraft:cobblestone", 10));
        ProgressionObservation obs = new ProgressionObservation(
                id, 100L, inv, false, false, false, false, true, false, true, true
        );
        ProgressionState state = graph.evaluate(obs, Map.of(MilestoneId.GATHER_WOOD, 5L));

        List<ProgressionRecommendation> recs = ProgressionPolicy.recommend(state, obs);

        var rec = recs.stream().filter(r -> r.milestoneId() == MilestoneId.SECURE_SHELTER).findFirst().orElseThrow();
        assertEquals("native_place", rec.skill());
        assertEquals(95.0, rec.need());
    }

    @Test
    void recommendsCraftingHoeWhenCobblestoneAndSticksAvailable() {
        InventorySummary inv = new InventorySummary(1L, 36, 2, 4, Map.of(
                "minecraft:cobblestone", 2,
                "minecraft:stick", 2
        ));
        ProgressionObservation obs = new ProgressionObservation(
                id, 110L, inv, true, false, false, false, false, false, true, true
        );
        ProgressionState state = graph.evaluate(obs, Map.of(
                MilestoneId.GATHER_WOOD, 5L,
                MilestoneId.CRAFT_PLANKS, 10L,
                MilestoneId.CRAFT_STICKS, 15L,
                MilestoneId.CRAFT_CRAFTING_TABLE, 20L,
                MilestoneId.DEPLOY_CRAFTING_TABLE, 25L
        ));

        assertTrue(state.activeFrontier().contains(MilestoneId.CRAFT_HOE));
        List<ProgressionRecommendation> recs = ProgressionPolicy.recommend(state, obs);

        var rec = recs.stream().filter(r -> r.milestoneId() == MilestoneId.CRAFT_HOE).findFirst().orElseThrow();
        assertEquals("native_craft", rec.skill());
        assertEquals("stone_hoe", rec.arguments().get("recipe"));
    }

    @Test
    void recommendsPlantingSeedsWhenHoeAndFarmlandAvailable() {
        InventorySummary inv = new InventorySummary(1L, 36, 2, 3, Map.of(
                "minecraft:stone_hoe", 1,
                "minecraft:wheat_seeds", 2
        ));
        ProgressionObservation obs = new ProgressionObservation(
                id, 120L, inv, false, false, false, false, false, false, true, true,
                true, false, false
        );
        ProgressionState state = graph.evaluate(obs, Map.of(
                MilestoneId.GATHER_WOOD, 5L,
                MilestoneId.CRAFT_HOE, 30L
        ));

        assertTrue(state.activeFrontier().contains(MilestoneId.ESTABLISH_FARM));
        List<ProgressionRecommendation> recs = ProgressionPolicy.recommend(state, obs);

        var rec = recs.stream().filter(r -> r.milestoneId() == MilestoneId.ESTABLISH_FARM).findFirst().orElseThrow();
        assertEquals("native_plant", rec.skill());
        assertEquals("minecraft:wheat_seeds", rec.arguments().get("item"));
    }

    @Test
    void recommendsCraftingBedWhenWoolAndPlanksAvailable() {
        InventorySummary inv = new InventorySummary(1L, 36, 2, 6, Map.of(
                "minecraft:white_wool", 3,
                "minecraft:oak_planks", 3
        ));
        ProgressionObservation obs = new ProgressionObservation(
                id, 130L, inv, true, false, false, false, false, false, true, true
        );
        ProgressionState state = graph.evaluate(obs, Map.of(
                MilestoneId.GATHER_WOOD, 5L,
                MilestoneId.CRAFT_PLANKS, 10L,
                MilestoneId.DEPLOY_CRAFTING_TABLE, 25L
        ));

        assertTrue(state.activeFrontier().contains(MilestoneId.CRAFT_BED));
        List<ProgressionRecommendation> recs = ProgressionPolicy.recommend(state, obs);

        var rec = recs.stream().filter(r -> r.milestoneId() == MilestoneId.CRAFT_BED).findFirst().orElseThrow();
        assertEquals("native_craft", rec.skill());
        assertEquals("white_bed", rec.arguments().get("recipe"));
    }

    @Test
    void farmHomeProjectFactoriesCreateStructuredSharedProjects() {
        var farmProj = dev.kindred.core.team.FarmHomeProjects.createFarmEstablishmentProject(
                dev.kindred.core.team.ProjectId.of("farm-1"), "Team Wheat Farm", "Cultivate wheat for food"
        );
        assertEquals(4, farmProj.tasks().size());
        assertTrue(farmProj.tasks().containsKey(dev.kindred.core.team.TaskId.of(farmProj.id(), "craft_hoe")));
        assertTrue(farmProj.tasks().containsKey(dev.kindred.core.team.TaskId.of(farmProj.id(), "till_soil")));
        assertTrue(farmProj.tasks().containsKey(dev.kindred.core.team.TaskId.of(farmProj.id(), "sow_crops")));
        assertTrue(farmProj.tasks().containsKey(dev.kindred.core.team.TaskId.of(farmProj.id(), "harvest_crops")));

        var shelterProj = dev.kindred.core.team.FarmHomeProjects.createShelterBedProject(
                dev.kindred.core.team.ProjectId.of("shelter-1"), "Home Base Shelter", "Place bed and anchor spawn"
        );
        assertEquals(3, shelterProj.tasks().size());
        assertTrue(shelterProj.tasks().containsKey(dev.kindred.core.team.TaskId.of(shelterProj.id(), "craft_bed")));
        assertTrue(shelterProj.tasks().containsKey(dev.kindred.core.team.TaskId.of(shelterProj.id(), "place_bed")));
        assertTrue(shelterProj.tasks().containsKey(dev.kindred.core.team.TaskId.of(shelterProj.id(), "anchor_spawn")));
    }
}
