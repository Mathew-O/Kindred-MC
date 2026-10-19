package dev.kindred.core.expedition;

import dev.kindred.core.InventorySummary;
import dev.kindred.core.progression.MilestoneGraph;
import dev.kindred.core.progression.MilestoneId;
import dev.kindred.core.progression.ProgressionObservation;
import dev.kindred.core.progression.ProgressionPolicy;
import dev.kindred.core.progression.ProgressionState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PortalTravelTest {

    @Test
    void testCoordinateScalingBetweenDimensions() {
        // Standard 8:1 ratio with Math.floorDiv
        assertEquals(0, Waypoint.overworldToNether(0));
        assertEquals(1, Waypoint.overworldToNether(8));
        assertEquals(12, Waypoint.overworldToNether(100));
        assertEquals(-1, Waypoint.overworldToNether(-1));
        assertEquals(-1, Waypoint.overworldToNether(-8));
        assertEquals(-2, Waypoint.overworldToNether(-9));
        assertEquals(-13, Waypoint.overworldToNether(-100));

        // Nether to Overworld
        assertEquals(0, Waypoint.netherToOverworld(0));
        assertEquals(800, Waypoint.netherToOverworld(100));
        assertEquals(-800, Waypoint.netherToOverworld(-100));

        // Waypoint conversion
        Waypoint overworldWp = Waypoint.of("portal_ow", 80, 64, -160, "minecraft:overworld", WaypointKind.NETHER_PORTAL, 100);
        Waypoint netherWp = overworldWp.toNetherCoordinates(null);
        assertEquals(10, netherWp.x());
        assertEquals(64, netherWp.y());
        assertEquals(-20, netherWp.z());
        assertEquals("minecraft:the_nether", netherWp.dimension());
        assertEquals(WaypointKind.NETHER_PORTAL, netherWp.kind());

        Waypoint backToOw = netherWp.toOverworldCoordinates("returned");
        assertEquals(80, backToOw.x());
        assertEquals(64, backToOw.y());
        assertEquals(-160, backToOw.z());
        assertEquals("minecraft:overworld", backToOw.dimension());
        assertEquals("returned", backToOw.name());
    }

    @Test
    void testStandardPortalFrameValidation() {
        // Construct a standard 4x5 portal frame along X axis (width 4, height 5, interior 2x3)
        // Interior bottom-left at (10, 64, 20)
        // Interior positions: (10, 64, 20), (10, 65, 20), (10, 66, 20),
        //                     (11, 64, 20), (11, 65, 20), (11, 66, 20)
        // Frame:
        // Bottom: (10, 63, 20), (11, 63, 20)
        // Top: (10, 67, 20), (11, 67, 20)
        // Left: (9, 64, 20), (9, 65, 20), (9, 66, 20)
        // Right: (12, 64, 20), (12, 65, 20), (12, 66, 20)
        // Total 10 obsidian blocks (no corners)

        Map<PortalFrame.BlockPos3i, String> world = new HashMap<>();

        // Add 10 frame blocks
        world.put(new PortalFrame.BlockPos3i(10, 63, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(11, 63, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(10, 67, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(11, 67, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(9, 64, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(9, 65, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(9, 66, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(12, 64, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(12, 65, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(12, 66, 20), PortalFrame.OBSIDIAN);

        // Interior is air
        world.put(new PortalFrame.BlockPos3i(10, 64, 20), PortalFrame.AIR);
        world.put(new PortalFrame.BlockPos3i(10, 65, 20), PortalFrame.AIR);
        world.put(new PortalFrame.BlockPos3i(10, 66, 20), PortalFrame.AIR);
        world.put(new PortalFrame.BlockPos3i(11, 64, 20), PortalFrame.AIR);
        world.put(new PortalFrame.BlockPos3i(11, 65, 20), PortalFrame.AIR);
        world.put(new PortalFrame.BlockPos3i(11, 66, 20), PortalFrame.AIR);

        PortalFrame frame = PortalFrame.evaluate(
                new PortalFrame.BlockPos3i(10, 64, 20), 2, 3, PortalFrame.Axis.X,
                pos -> world.getOrDefault(pos, PortalFrame.AIR)
        );

        assertTrue(frame.valid(), "10-block standard portal frame without corners is valid");
        assertEquals(10, frame.obsidianCount());
        assertNull(frame.failureReason());

        // Add 4 corners -> total 14 obsidian
        world.put(new PortalFrame.BlockPos3i(9, 63, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(12, 63, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(9, 67, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(12, 67, 20), PortalFrame.OBSIDIAN);

        PortalFrame fullFrame = PortalFrame.evaluate(
                new PortalFrame.BlockPos3i(10, 64, 20), 2, 3, PortalFrame.Axis.X,
                pos -> world.getOrDefault(pos, PortalFrame.AIR)
        );

        assertTrue(fullFrame.valid());
        assertEquals(14, fullFrame.obsidianCount(), "Full 14-block frame with corners counted");

        // Detection should find this frame
        Optional<PortalFrame> detected = PortalFrame.detect(
                new PortalFrame.BlockPos3i(10, 64, 20),
                pos -> world.getOrDefault(pos, PortalFrame.AIR)
        );
        assertTrue(detected.isPresent());
        assertEquals(PortalFrame.Axis.X, detected.get().axis());
    }

    @Test
    void testInvalidPortalFrameRejections() {
        Map<PortalFrame.BlockPos3i, String> world = new HashMap<>();

        // Missing top blocks
        world.put(new PortalFrame.BlockPos3i(10, 63, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(11, 63, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(9, 64, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(9, 65, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(9, 66, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(12, 64, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(12, 65, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(12, 66, 20), PortalFrame.OBSIDIAN);

        PortalFrame frame = PortalFrame.evaluate(
                new PortalFrame.BlockPos3i(10, 64, 20), 2, 3, PortalFrame.Axis.X,
                pos -> world.getOrDefault(pos, PortalFrame.AIR)
        );

        assertFalse(frame.valid(), "Incomplete frame missing top obsidian must be rejected");
        assertTrue(frame.failureReason().contains("top frame position"));

        // Obstructed interior
        world.put(new PortalFrame.BlockPos3i(10, 67, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(11, 67, 20), PortalFrame.OBSIDIAN);
        world.put(new PortalFrame.BlockPos3i(10, 64, 20), "minecraft:dirt"); // Obstructed!

        PortalFrame obstructed = PortalFrame.evaluate(
                new PortalFrame.BlockPos3i(10, 64, 20), 2, 3, PortalFrame.Axis.X,
                pos -> world.getOrDefault(pos, PortalFrame.AIR)
        );
        assertFalse(obstructed.valid(), "Obstructed interior must be rejected");
        assertTrue(obstructed.failureReason().contains("obstructed"));
    }

    @Test
    void testNetherMilestoneProgression() {
        MilestoneGraph graph = MilestoneGraph.canonical();
        UUID companionId = UUID.randomUUID();

        // 1. Observation with iron pickaxe and flint and steel in Overworld
        Map<String, Integer> items = new HashMap<>();
        items.put("minecraft:iron_pickaxe", 1);
        items.put("minecraft:flint_and_steel", 1);
        InventorySummary inv = new InventorySummary(1, 36, 2, 2, items);

        ProgressionObservation obsOw = new ProgressionObservation(
                companionId, 100, inv, true, true, false, false,
                false, true, true, true, false, false, false, "minecraft:overworld"
        );

        // Precondition history: has CRAFT_IRON_PICKAXE
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
        history.put(MilestoneId.DEPLOY_FURNACE, 95L);
        history.put(MilestoneId.SMELT_RESOURCES, 100L);
        history.put(MilestoneId.CRAFT_IRON_PICKAXE, 110L);

        ProgressionState stateOw = graph.evaluate(obsOw, history);
        assertFalse(stateOw.isAchieved(MilestoneId.ENTER_NETHER),
                "Obsidian possession cannot substitute for physically entering the Nether");
        assertTrue(stateOw.activeFrontier().contains(MilestoneId.ENTER_NETHER),
                "ENTER_NETHER is on active frontier once iron pickaxe is achieved");

        var rec = ProgressionPolicy.recommend(stateOw, obsOw);
        var netherRec = rec.stream().filter(r -> r.milestoneId() == MilestoneId.ENTER_NETHER).findFirst();
        assertTrue(netherRec.isPresent(), "Progression policy recommends ENTER_NETHER candidate");
        assertEquals("native_ignite_portal", netherRec.get().skill(),
                "Progression recommends native_ignite_portal when holding flint and steel in Overworld");

        // 2. Observation after arriving in the Nether
        ProgressionObservation obsNether = new ProgressionObservation(
                companionId, 200, inv, false, false, false, false,
                false, false, false, false, false, false, false, "minecraft:the_nether"
        );

        ProgressionState stateNether = graph.evaluate(obsNether, history);
        assertTrue(stateNether.isAchieved(MilestoneId.ENTER_NETHER),
                "ENTER_NETHER achieved upon entering the Nether dimension");
        assertTrue(stateNether.activeFrontier().contains(MilestoneId.GATHER_BLAZE_RODS),
                "GATHER_BLAZE_RODS unlocked on frontier");
        assertTrue(stateNether.activeFrontier().contains(MilestoneId.GATHER_NETHER_WART),
                "GATHER_NETHER_WART unlocked on frontier");

        var recNether = ProgressionPolicy.recommend(stateNether, obsNether);
        var blazeRec = recNether.stream().filter(r -> r.milestoneId() == MilestoneId.GATHER_BLAZE_RODS).findFirst();
        assertTrue(blazeRec.isPresent(), "GATHER_BLAZE_RODS recommended in Nether");
        assertEquals("native_traverse", blazeRec.get().skill());
        assertEquals("nether_fortress", blazeRec.get().arguments().get("target"));
        ProgressionObservation wartPatchObs = new ProgressionObservation(
                companionId, 205, inv, false, false, false, false,
                false, false, false, false, false, false, false, "minecraft:the_nether", true
        );
        var wartGather = ProgressionPolicy.recommend(graph.evaluate(wartPatchObs, history), wartPatchObs).stream()
                .filter(r -> r.milestoneId() == MilestoneId.GATHER_NETHER_WART).findFirst().orElseThrow();
        assertEquals("native_gather", wartGather.skill());
        assertEquals("minecraft:nether_wart", wartGather.arguments().get("resource"));

        // Physical resource possession completes both E-15 resource milestones.
        Map<String, Integer> earnedResources = new HashMap<>(items);
        earnedResources.put("minecraft:blaze_rod", 1);
        earnedResources.put("minecraft:nether_wart", 1);
        InventorySummary resourceInventory = new InventorySummary(1, 36, 4, 4, earnedResources);
        ProgressionObservation resourceObsNether = new ProgressionObservation(
                companionId, 210, resourceInventory, false, false, false, false,
                false, false, false, false, false, false, false, "minecraft:the_nether"
        );
        ProgressionState resourceState = graph.evaluate(resourceObsNether, history);
        assertTrue(resourceState.isAchieved(MilestoneId.GATHER_BLAZE_RODS));
        assertTrue(resourceState.isAchieved(MilestoneId.GATHER_NETHER_WART));
        assertTrue(ProgressionPolicy.recommend(resourceState, resourceObsNether).stream()
                .noneMatch(r -> r.milestoneId() == MilestoneId.GATHER_BLAZE_RODS
                        || r.milestoneId() == MilestoneId.GATHER_NETHER_WART),
                "Completed Nether resource milestones must stop requesting fortress search");

        // Once recorded in history, a completed resource objective remains achieved
        // after returning to the Overworld or consuming the physical item.
        Map<MilestoneId, Long> earnedHistory = new HashMap<>(history);
        earnedHistory.put(MilestoneId.ENTER_NETHER, 200L);
        earnedHistory.put(MilestoneId.GATHER_BLAZE_RODS, 210L);
        earnedHistory.put(MilestoneId.GATHER_NETHER_WART, 210L);
        ProgressionObservation returnedWithoutResources = new ProgressionObservation(
                companionId, 300, inv, true, true, false, false,
                false, true, true, true, false, false, false, "minecraft:overworld"
        );
        ProgressionState returnedState = graph.evaluate(returnedWithoutResources, earnedHistory);
        assertTrue(returnedState.snapshot(MilestoneId.ENTER_NETHER).orElseThrow().historicallyAchieved());
        assertTrue(returnedState.snapshot(MilestoneId.GATHER_BLAZE_RODS).orElseThrow().historicallyAchieved());
        assertTrue(returnedState.snapshot(MilestoneId.GATHER_NETHER_WART).orElseThrow().historicallyAchieved());
        assertTrue(returnedState.recoveryFrontier().contains(MilestoneId.GATHER_BLAZE_RODS));
        assertTrue(returnedState.recoveryFrontier().contains(MilestoneId.GATHER_NETHER_WART));
    }
}
