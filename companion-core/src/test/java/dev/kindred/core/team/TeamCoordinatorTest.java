package dev.kindred.core.team;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TeamCoordinatorTest {
    private TeamCoordinator coordinator;
    private StockLedger stockLedger;
    private UUID player1;
    private UUID player2;
    private ProjectId projectId;

    @BeforeEach
    void setUp() {
        stockLedger = new StockLedger();
        coordinator = new TeamCoordinator(stockLedger);
        player1 = UUID.randomUUID();
        player2 = UUID.randomUUID();
        projectId = ProjectId.of("build_tools");

        SharedProject project = SharedProject.create(projectId, "Tool Supply", "Gather wood and craft pickaxes");
        coordinator.registerProject(project);
    }

    @Test
    void taskDependenciesEnforceStrictOrder() {
        TaskId task1 = TaskId.random();
        TaskId task2 = TaskId.random();

        SharedTask gatherWood = SharedTask.open(task1, projectId, "Gather Wood", "native_gather",
                Map.of("produces_item", "minecraft:oak_log", "produces_count", "4"), Set.of());
        SharedTask craftPlanks = SharedTask.open(task2, projectId, "Craft Planks", "native_craft",
                Map.of("recipe", "oak_planks"), Set.of(task1));

        coordinator.addTask(projectId, gatherWood);
        coordinator.addTask(projectId, craftPlanks);

        // Task 2 should not be eligible while Task 1 is incomplete
        var eligibleInitial = coordinator.findEligibleTasks(player1);
        assertEquals(1, eligibleInitial.size());
        assertEquals(task1, eligibleInitial.get(0).id());

        // Player 1 claims and completes Task 1
        var claimed = coordinator.claimTask(task1, player1, 100L, 200L);
        assertTrue(claimed.isPresent());
        coordinator.completeTask(task1, player1, "Gathered 4 oak logs");

        // Now Task 2 should become eligible
        var eligibleAfter = coordinator.findEligibleTasks(player2);
        assertEquals(1, eligibleAfter.size());
        assertEquals(task2, eligibleAfter.get(0).id());

        // Task 1 should be COMPLETED and stock should have increased by 4
        assertEquals(SharedTask.TaskState.COMPLETED, coordinator.findTask(task1).orElseThrow().state());
        assertEquals(4, stockLedger.totalStock("minecraft:oak_log"));
    }

    @Test
    void singleAssigneeOwnershipAndContentionRejection() {
        TaskId task1 = TaskId.random();
        SharedTask task = SharedTask.open(task1, projectId, "Mine Stone", "native_mine", Map.of(), Set.of());
        coordinator.addTask(projectId, task);

        // Player 1 claims task
        var claim1 = coordinator.claimTask(task1, player1, 100L, 200L);
        assertTrue(claim1.isPresent());
        assertEquals(player1, claim1.get().assignedPlayerId());
        assertEquals(SharedTask.TaskState.CLAIMED, claim1.get().state());

        // Player 2 attempts to claim the exact same task -> must be rejected
        var claim2 = coordinator.claimTask(task1, player2, 105L, 200L);
        assertTrue(claim2.isEmpty(), "Contending claim must be rejected");

        // Player 2 cannot complete Player 1's task
        assertFalse(coordinator.completeTask(task1, player2, "Faked evidence"));
    }

    @Test
    void stockReservationPreventsDoubleSpending() {
        stockLedger.registerStock("minecraft:iron_ore", 10);

        TaskId task1 = TaskId.random();
        TaskId task2 = TaskId.random();

        // Task 1 needs 6 iron ore; Task 2 needs 5 iron ore
        SharedTask smelt1 = SharedTask.open(task1, projectId, "Smelt Batch 1", "native_smelt",
                Map.of("reserved_item", "minecraft:iron_ore", "reserved_count", "6"), Set.of());
        SharedTask smelt2 = SharedTask.open(task2, projectId, "Smelt Batch 2", "native_smelt",
                Map.of("reserved_item", "minecraft:iron_ore", "reserved_count", "5"), Set.of());

        coordinator.addTask(projectId, smelt1);
        coordinator.addTask(projectId, smelt2);

        // Player 1 claims Task 1 -> reserves 6, leaving 4 unreserved
        var claim1 = coordinator.claimTask(task1, player1, 100L, 200L);
        assertTrue(claim1.isPresent());
        assertEquals(4, stockLedger.unreservedCount("minecraft:iron_ore"));
        assertEquals(6, stockLedger.reservedCount("minecraft:iron_ore"));

        // Player 2 attempts to claim Task 2 (requires 5) -> rejected because only 4 unreserved
        var claim2 = coordinator.claimTask(task2, player2, 100L, 200L);
        assertTrue(claim2.isEmpty(), "Must reject claim exceeding unreserved stock to prevent double spending");

        // Player 1 completes Task 1 -> consumed 6 iron ore
        assertTrue(coordinator.completeTask(task1, player1, "Smelted 6 iron ingots"));
        assertEquals(4, stockLedger.totalStock("minecraft:iron_ore"));
        assertEquals(0, stockLedger.reservedCount("minecraft:iron_ore"));

        // Add 3 more iron ore to stock -> total 7
        stockLedger.addStock("minecraft:iron_ore", 3);
        assertEquals(7, stockLedger.unreservedCount("minecraft:iron_ore"));

        // Now Player 2 can claim Task 2 (requires 5)
        var claim2Retry = coordinator.claimTask(task2, player2, 150L, 200L);
        assertTrue(claim2Retry.isPresent());
        assertEquals(2, stockLedger.unreservedCount("minecraft:iron_ore"));
        assertEquals(5, stockLedger.reservedCount("minecraft:iron_ore"));
    }

    @Test
    void playerDeathRevokesTaskAndReleasesStockReservation() {
        stockLedger.registerStock("minecraft:oak_log", 8);

        TaskId task1 = TaskId.random();
        SharedTask task = SharedTask.open(task1, projectId, "Craft Planks", "native_craft",
                Map.of("reserved_item", "minecraft:oak_log", "reserved_count", "4"), Set.of());
        coordinator.addTask(projectId, task);

        // Player 1 claims task and reserves 4 logs
        var claim1 = coordinator.claimTask(task1, player1, 100L, 200L);
        assertTrue(claim1.isPresent());
        assertEquals(4, stockLedger.unreservedCount("minecraft:oak_log"));

        // Player 1 dies
        coordinator.handlePlayerDeath(player1);

        // Task should be revoked and unreserved stock restored to 8
        var updatedTask = coordinator.findTask(task1).orElseThrow();
        assertEquals(SharedTask.TaskState.REVOKED, updatedTask.state());
        assertNull(updatedTask.assignedPlayerId());
        assertEquals(8, stockLedger.unreservedCount("minecraft:oak_log"));
        assertEquals(0, stockLedger.reservedCount("minecraft:oak_log"));

        // Player 2 can now claim the revoked task
        var claim2 = coordinator.claimTask(task1, player2, 120L, 200L);
        assertTrue(claim2.isPresent());
        assertEquals(player2, claim2.get().assignedPlayerId());
        assertEquals(4, stockLedger.unreservedCount("minecraft:oak_log"));
    }

    @Test
    void playerStopRevokesTaskAndReleasesReservation() {
        stockLedger.registerStock("minecraft:cobblestone", 10);

        TaskId task1 = TaskId.random();
        SharedTask task = SharedTask.open(task1, projectId, "Craft Furnace", "native_craft",
                Map.of("reserved_item", "minecraft:cobblestone", "reserved_count", "8"), Set.of());
        coordinator.addTask(projectId, task);

        coordinator.claimTask(task1, player1, 100L, 200L);
        assertEquals(2, stockLedger.unreservedCount("minecraft:cobblestone"));

        // Operator issues stop to player 1
        coordinator.handlePlayerStop(player1);

        assertEquals(10, stockLedger.unreservedCount("minecraft:cobblestone"));
        var updatedTask = coordinator.findTask(task1).orElseThrow();
        assertEquals(SharedTask.TaskState.REVOKED, updatedTask.state());
    }

    @Test
    void taskLeaseExpiryReopensTaskForPeers() {
        TaskId task1 = TaskId.random();
        SharedTask task = SharedTask.open(task1, projectId, "Explore Cave", "native_traverse", Map.of(), Set.of());
        coordinator.addTask(projectId, task);

        // Claim with 50-tick lease at tick 100 (expires at tick 150)
        coordinator.claimTask(task1, player1, 100L, 50L);

        // At tick 140, not expired
        coordinator.handleTickExpiry(140L);
        assertEquals(SharedTask.TaskState.CLAIMED, coordinator.findTask(task1).orElseThrow().state());

        // At tick 160, expired -> revoked and reopened
        coordinator.handleTickExpiry(160L);
        assertEquals(SharedTask.TaskState.OPEN, coordinator.findTask(task1).orElseThrow().state());

        // Player 2 can now claim it
        var claim2 = coordinator.claimTask(task1, player2, 165L, 50L);
        assertTrue(claim2.isPresent());
        assertEquals(player2, claim2.get().assignedPlayerId());
    }

    @Test
    void projectCompletionTransitionsStateWhenAllTasksComplete() {
        TaskId task1 = TaskId.random();
        TaskId task2 = TaskId.random();

        coordinator.addTask(projectId, SharedTask.open(task1, projectId, "T1", "native_gather", Map.of(), Set.of()));
        coordinator.addTask(projectId, SharedTask.open(task2, projectId, "T2", "native_craft", Map.of(), Set.of()));

        assertEquals(SharedProject.ProjectState.OPEN, coordinator.getProject(projectId).orElseThrow().state());

        coordinator.claimTask(task1, player1, 100L, 100L);
        assertEquals(SharedProject.ProjectState.IN_PROGRESS, coordinator.getProject(projectId).orElseThrow().state());

        coordinator.completeTask(task1, player1, "done1");
        assertEquals(SharedProject.ProjectState.IN_PROGRESS, coordinator.getProject(projectId).orElseThrow().state());

        coordinator.claimTask(task2, player2, 100L, 100L);
        coordinator.completeTask(task2, player2, "done2");

        assertEquals(SharedProject.ProjectState.COMPLETED, coordinator.getProject(projectId).orElseThrow().state());
    }
}
