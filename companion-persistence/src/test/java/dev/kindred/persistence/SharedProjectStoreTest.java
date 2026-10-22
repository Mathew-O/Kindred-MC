package dev.kindred.persistence;

import dev.kindred.core.team.ProjectId;
import dev.kindred.core.team.SharedProject;
import dev.kindred.core.team.SharedTask;
import dev.kindred.core.team.StockReservation;
import dev.kindred.core.team.TaskId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class SharedProjectStoreTest {
    @TempDir
    Path tempDir;

    private SqliteCompanionStore store;
    private Path dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("companions.sqlite");
        store = SqliteCompanionStore.open(dbPath, 10).join();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.closeAsync().join();
        }
    }

    @Test
    void sharedProjectsAndTasksRoundTrip() {
        ProjectId pid = ProjectId.of("build_shelter");
        TaskId t1 = TaskId.random();
        TaskId t2 = TaskId.random();
        UUID player1 = UUID.randomUUID();

        StockReservation res = StockReservation.active(
                UUID.randomUUID(), t2, pid, player1, "minecraft:cobblestone", 8, "chest:0,64,0"
        );

        SharedTask task1 = SharedTask.open(t1, pid, "Gather Wood", "native_gather",
                Map.of("resource", "minecraft:oak_log", "count", "16"), Set.of());
        SharedTask task2 = new SharedTask(t2, pid, "Build Frame", "native_place",
                Map.of("block", "minecraft:cobblestone"), Set.of(t1), player1,
                SharedTask.TaskState.CLAIMED, Optional.of(res), 1L, 100L, 500L, null);

        SharedProject project = SharedProject.create(pid, "Shelter Project", "Build safe night shelter")
                .withTask(task1)
                .withTask(task2);

        SharedProject created = store.createProject(project).join();
        assertEquals(pid, created.id());

        // Find project
        Optional<SharedProject> loadedOpt = store.findProject(pid).join();
        assertTrue(loadedOpt.isPresent());
        SharedProject loaded = loadedOpt.get();
        assertEquals("Shelter Project", loaded.name());
        assertEquals(2, loaded.tasks().size());

        SharedTask loadedT1 = loaded.getTask(t1).orElseThrow();
        assertEquals("Gather Wood", loadedT1.title());
        assertEquals("native_gather", loadedT1.skill());
        assertEquals("16", loadedT1.arguments().get("count"));
        assertTrue(loadedT1.dependencies().isEmpty());

        SharedTask loadedT2 = loaded.getTask(t2).orElseThrow();
        assertEquals("Build Frame", loadedT2.title());
        assertEquals(player1, loadedT2.assignedPlayerId());
        assertEquals(SharedTask.TaskState.CLAIMED, loadedT2.state());
        assertTrue(loadedT2.dependencies().contains(t1));
        assertTrue(loadedT2.stockReservation().isPresent());
        assertEquals("minecraft:cobblestone", loadedT2.stockReservation().get().itemId());
        assertEquals(8, loadedT2.stockReservation().get().quantity());

        // List projects
        var allProjects = store.listProjects().join();
        assertEquals(1, allProjects.size());
        assertEquals(pid, allProjects.get(0).id());
    }

    @Test
    void projectRevisionEnforcesOptimisticLocking() {
        ProjectId pid = ProjectId.of("iron_gear");
        SharedProject project = SharedProject.create(pid, "Iron Gear", "Smelt iron and craft armor");
        store.createProject(project).join();

        // Update with expected revision 0 -> succeeds, revision becomes 1
        SharedProject updated = project.withState(SharedProject.ProjectState.IN_PROGRESS);
        store.saveProject(updated, 0).join();

        // Attempt to update with stale revision 0 -> throws ConcurrentModificationException
        assertThrows(CompletionException.class, () -> store.saveProject(updated.withState(SharedProject.ProjectState.COMPLETED), 0).join());
    }

    @Test
    void immutableDesignRevisionRoundTripsAndRejectsConflictingReuse() {
        String hash = "a".repeat(64);
        StoredDesignRevision first = new StoredDesignRevision("workshop.design", 1, 0L, UUID.randomUUID(),
                "minecraft:overworld", "observation.1", 4, hash, hash, "generator.1", "DRAFT", "{}", "{}");
        assertEquals(first, store.saveDesignRevision(first).join());
        assertEquals(first, store.saveDesignRevision(first).join(), "same immutable record is idempotent");
        assertEquals(first, store.findDesignRevision("workshop.design", 1).join().orElseThrow());
        DesignBinding binding = new DesignBinding("workshop.design", 1, "project.workshop", DesignBinding.State.DRAFT, hash, 0);
        assertEquals(binding, store.createDesignBinding(binding).join());
        DesignBinding validated = binding.transition(DesignBinding.State.VALIDATED);
        assertEquals(validated, store.transitionDesignBinding(validated, 0).join());
        DesignBinding accepted = validated.transition(DesignBinding.State.ACCEPTED);
        assertEquals(accepted, store.transitionDesignBinding(accepted, 1).join());
        assertThrows(IllegalStateException.class, () -> accepted.transition(DesignBinding.State.DRAFT));
        StoredDesignRevision conflict = new StoredDesignRevision("workshop.design", 1, 0L, first.worldId(),
                "minecraft:overworld", "observation.1", 4, "b".repeat(64), hash, "generator.1", "DRAFT", "{}", "{}");
        assertThrows(CompletionException.class, () -> store.saveDesignRevision(conflict).join());
        StoredDesignRevision skippedParent = new StoredDesignRevision("workshop.design", 3, 1L, first.worldId(),
                "minecraft:overworld", "observation.1", 4, hash, hash, "generator.1", "DRAFT", "{}", "{}");
        assertThrows(CompletionException.class, () -> store.saveDesignRevision(skippedParent).join());
    }

    @Test
    void taskRevisionEnforcesOptimisticLocking() {
        ProjectId pid = ProjectId.of("mine_iron");
        TaskId tid = TaskId.random();
        SharedTask task = SharedTask.open(tid, pid, "Mine", "native_mine", Map.of(), Set.of());
        SharedProject project = SharedProject.create(pid, "Mine", "Desc").withTask(task);
        store.createProject(project).join();

        SharedTask claimed = task.claim(UUID.randomUUID(), 100L, 200L, Optional.empty());
        store.saveTask(claimed, 0).join();

        // Stale update with revision 0
        assertThrows(CompletionException.class, () -> store.saveTask(claimed.start(), 0).join());
    }

    @Test
    void startupReconciliationReleasesActiveReservationsAndResetsInFlightTasks() {
        ProjectId pid = ProjectId.of("recover_test");
        TaskId tid = TaskId.random();
        UUID player1 = UUID.randomUUID();

        StockReservation res = StockReservation.active(
                UUID.randomUUID(), tid, pid, player1, "minecraft:iron_ingot", 5, "chest:10,64,10"
        );

        SharedTask task = new SharedTask(tid, pid, "Craft Iron Pickaxe", "native_craft",
                Map.of("recipe", "iron_pickaxe"), Set.of(), player1,
                SharedTask.TaskState.IN_PROGRESS, Optional.of(res), 1L, 50L, 200L, null);

        SharedProject project = SharedProject.create(pid, "Recover Test", "Desc").withTask(task);
        store.createProject(project).join();

        // Close store (simulating crash while task is in flight)
        store.closeAsync().join();

        // Reopen store -> recover() executes
        SqliteCompanionStore reopened = SqliteCompanionStore.open(dbPath, 10).join();
        try {
            SharedProject loaded = reopened.findProject(pid).join().orElseThrow();
            SharedTask recoveredTask = loaded.getTask(tid).orElseThrow();

            // Task must be reset to OPEN and unassigned
            assertEquals(SharedTask.TaskState.OPEN, recoveredTask.state(), "In-flight task must be recovered to OPEN");
            assertNull(recoveredTask.assignedPlayerId(), "Assignee must be cleared on recovery");

            // Reservation must be RELEASED (so stock is not locked or double-spent)
            assertTrue(recoveredTask.stockReservation().isEmpty(), "Active reservation must be cleared from task");
        } finally {
            reopened.closeAsync().join();
        }
    }
}
