package dev.kindred.fabric;

import dev.kindred.core.team.ProjectId;
import dev.kindred.core.team.SharedProject;
import dev.kindred.core.team.SharedTask;
import dev.kindred.core.team.TaskId;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: a GameTest: in a real test world, a player's team bookkeeping is checked. A task that
 * depends on another cannot be taken until that one is done; a task one player has taken cannot be taken by
 * another; materials set aside for one task cannot be promised to a second; a player that stops or dies gives
 * up its task and the materials it set aside, so a teammate can take them; and a stored project's progress is
 * saved as it happens, crediting only what was really gathered.
 *
 * Technical: Acceptance GameTests for Shared Projects and AI-Player Task Assignments (E-11, TEAM-01,
 * TEAM-02):
 * 1. Cooperative task dependency order enforced across peers.
 * 2. Contending claims rejected with atomic single-assignee ownership.
 * 3. Shared stock reservations prevent double spending of materials across peers.
 * 4. Player stop or death cleanly revokes assignments and releases reservations for peers.
 */
public final class NativeTeamGameTests implements FabricGameTest {

    /**
     * A project the store holds has its every change saved as it happens, through the store's one writer: read back
     * after a claim and a completion, the task is completed as in memory, at the same revision, its evidence kept, and
     * only the output observed credited (two logs of a declared four).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "teamSaved", tickLimit = 400)
    public void aStoredProjectsClaimAndCompletionAreSavedAsTheyHappen(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        ProjectId projId = ProjectId.of("saved-" + UUID.randomUUID());
        TaskId gather = TaskId.random();
        java.util.concurrent.CompletableFuture<?>[] created = {null};
        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletableFuture<java.util.Optional<SharedProject>>[] read = new java.util.concurrent.CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ledgerer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> created[0] = f.runtime.createSharedProject(SharedProject.create(projId, "Logs", "Gather logs")
                        .withTask(SharedTask.open(gather, projId, "Gather", "native_gather",
                                Map.of("produces_item", "minecraft:oak_log", "produces_count", "4"), Set.of()))))
                .createAndAdd(() -> context.assertTrue(created[0].isDone() && !created[0].isCompletedExceptionally()
                        && f.runtime.teamCoordinator().getProject(projId).isPresent(), "Created in the store, then known"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.teamCoordinator().claimTask(gather, f.id, 0, 1200).isPresent(), "Claimed");
                    context.assertTrue(f.runtime.teamCoordinator().completeTask(gather, f.id, "gathered two", Map.of("minecraft:oak_log", 2)), "Completed");
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().totalStock("minecraft:oak_log") == 2, "Two credited, as observed");
                    read[0] = f.runtime.savedSharedProject(projId);
                })
                .createAndAdd(() -> {
                    context.assertTrue(read[0].isDone(), "Read back");
                    var saved = read[0].join().orElseThrow().getTask(gather).orElseThrow();
                    var live = f.runtime.teamCoordinator().findTask(gather).orElseThrow();
                    context.assertTrue(saved.state() == SharedTask.TaskState.COMPLETED, "Saved completed: " + saved.state());
                    context.assertTrue(saved.revision() == live.revision(), "Saved at its revision: " + saved.revision() + " for " + live.revision());
                    context.assertTrue("gathered two".equals(saved.completionEvidence()), "Its evidence kept: " + saved.completionEvidence());
                    context.assertTrue(f.id.equals(saved.assignedPlayerId()), "By its player");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void cooperativeTeamTaskOrderEnforcedAcrossPeers(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        ProjectId projId = ProjectId.of("wood_infrastructure");
        TaskId t1 = TaskId.random();
        TaskId t2 = TaskId.random();
        UUID peerId = UUID.randomUUID();

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "TeamDepAdvancer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Register project with Task 1 (gather) and Task 2 (craft, depends on Task 1)
                    SharedTask task1 = SharedTask.open(t1, projId, "Gather Wood", "native_wait", Map.of("seconds", "1"), Set.of());
                    SharedTask task2 = SharedTask.open(t2, projId, "Craft Planks", "native_wait", Map.of("seconds", "1"), Set.of(t1));
                    SharedProject project = SharedProject.create(projId, "Wood Infrastructure Project", "Setup basic wood stockpile")
                            .withTask(task1)
                            .withTask(task2);
                    f.runtime.teamCoordinator().registerProject(project);

                    // Task 2 should NOT be eligible because Task 1 is incomplete
                    var eligible = f.runtime.teamCoordinator().findEligibleTasks(peerId);
                    context.assertTrue(eligible.size() == 1 && eligible.getFirst().id().equals(t1), "Only Task 1 is eligible initially");

                    // Contending attempt by peer to claim Task 2 fails
                    var claim2Early = f.runtime.teamCoordinator().claimTask(t2, peerId, 0, 1200);
                    context.assertTrue(claim2Early.isEmpty(), "Task 2 cannot be claimed before dependencies are completed");

                    // Native player claims and starts Task 1
                    var claim1 = f.runtime.teamCoordinator().claimTask(t1, f.id, 0, 1200);
                    context.assertTrue(claim1.isPresent(), "Native player claims Task 1");
                    f.runtime.teamCoordinator().startTask(t1, f.id);

                    // Complete Task 1
                    boolean t1Done = f.runtime.teamCoordinator().completeTask(t1, f.id, "Gathered 4 oak logs");
                    context.assertTrue(t1Done, "Task 1 completed by Native player");

                    // Now Task 2 should be eligible
                    var eligibleAfter = f.runtime.teamCoordinator().findEligibleTasks(peerId);
                    context.assertTrue(eligibleAfter.size() == 1 && eligibleAfter.getFirst().id().equals(t2), "Task 2 is now eligible for peer");

                    // Peer claims and completes Task 2
                    var claim2 = f.runtime.teamCoordinator().claimTask(t2, peerId, 10, 1200);
                    context.assertTrue(claim2.isPresent(), "Peer claims Task 2");
                    f.runtime.teamCoordinator().startTask(t2, peerId);
                    boolean t2Done = f.runtime.teamCoordinator().completeTask(t2, peerId, "Crafted 16 planks");
                    context.assertTrue(t2Done, "Task 2 completed by peer");

                    // Overall project transitions to COMPLETED
                    var finishedProj = f.runtime.teamCoordinator().getProject(projId).orElseThrow();
                    context.assertTrue(finishedProj.state() == SharedProject.ProjectState.COMPLETED, "Project transitions to COMPLETED when all tasks finish");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void contendingClaimsRejectedWithSingleAssigneeOwnership(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        ProjectId projId = ProjectId.of("mining_outpost");
        TaskId t1 = TaskId.random();
        UUID peerId = UUID.randomUUID();

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "TeamClaimant"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    SharedTask task1 = SharedTask.open(t1, projId, "Excavate Tunnel", "native_wait", Map.of("seconds", "1"), Set.of());
                    SharedProject project = SharedProject.create(projId, "Mining Outpost", "Dig exploratory shaft").withTask(task1);
                    f.runtime.teamCoordinator().registerProject(project);

                    // Native player claims Task 1
                    var claim1 = f.runtime.teamCoordinator().claimTask(t1, f.id, 0, 1200);
                    context.assertTrue(claim1.isPresent(), "Native player successfully claims open task");

                    // Peer attempts to claim the same task -> contention rejected
                    var claim2 = f.runtime.teamCoordinator().claimTask(t1, peerId, 0, 1200);
                    context.assertTrue(claim2.isEmpty(), "Contending claim by peer is rejected");

                    // Task preserves sole ownership by native player
                    var task = f.runtime.teamCoordinator().findTask(t1).orElseThrow();
                    context.assertTrue(f.id.equals(task.assignedPlayerId()), "Single-assignee ownership maintained for native player");
                    context.assertTrue(task.state() == SharedTask.TaskState.CLAIMED, "Task state remains CLAIMED");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void sharedStockReservationPreventsDoubleSpendingAcrossPeers(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        ProjectId projId = ProjectId.of("iron_works");
        TaskId t1 = TaskId.random();
        TaskId t2 = TaskId.random();
        UUID peerId = UUID.randomUUID();

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "TeamStockWorker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Seed stock ledger with 10 iron ingots
                    f.runtime.teamCoordinator().stockLedger().addStock("minecraft:iron_ingot", 10);
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().unreservedCount("minecraft:iron_ingot") == 10, "10 ingots available");

                    // Task 1 requires 6 ingots, Task 2 requires 5 ingots
                    SharedTask task1 = SharedTask.open(t1, projId, "Craft Armor", "native_wait",
                            Map.of("reserved_item", "minecraft:iron_ingot", "reserved_count", "6"), Set.of());
                    SharedTask task2 = SharedTask.open(t2, projId, "Craft Tools", "native_wait",
                            Map.of("reserved_item", "minecraft:iron_ingot", "reserved_count", "5"), Set.of());

                    SharedProject project = SharedProject.create(projId, "Iron Works", "Forge expedition gear")
                            .withTask(task1)
                            .withTask(task2);
                    f.runtime.teamCoordinator().registerProject(project);

                    // Native player claims Task 1 (reserves 6 ingots)
                    var claim1 = f.runtime.teamCoordinator().claimTask(t1, f.id, 0, 1200);
                    context.assertTrue(claim1.isPresent(), "Native player claims Task 1 and reserves 6 ingots");
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().unreservedCount("minecraft:iron_ingot") == 4, "4 ingots unreserved");

                    // Peer attempts to claim Task 2 (requires 5 ingots, but only 4 unreserved) -> REJECTED
                    var claim2 = f.runtime.teamCoordinator().claimTask(t2, peerId, 0, 1200);
                    context.assertTrue(claim2.isEmpty(), "Claim 2 rejected to prevent double spending of limited materials");

                    // Native player completes Task 1 (consumes 6 ingots)
                    f.runtime.teamCoordinator().completeTask(t1, f.id, "Armor forged");
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().totalStock("minecraft:iron_ingot") == 4, "Total stock is now 4");
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().unreservedCount("minecraft:iron_ingot") == 4, "Unreserved stock is 4");

                    // Add 2 more ingots to stock (total 6 unreserved)
                    f.runtime.teamCoordinator().stockLedger().addStock("minecraft:iron_ingot", 2);
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().unreservedCount("minecraft:iron_ingot") == 6, "Unreserved is 6");

                    // Now peer can successfully claim Task 2
                    var claim2Retry = f.runtime.teamCoordinator().claimTask(t2, peerId, 10, 1200);
                    context.assertTrue(claim2Retry.isPresent(), "Peer claims Task 2 once stock is replenished");
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().unreservedCount("minecraft:iron_ingot") == 1, "1 ingot remaining unreserved");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void playerStopOrDeathRevokesTaskAndReleasesStockForPeers(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        ProjectId projId = ProjectId.of("diamond_tools");
        TaskId t1 = TaskId.random();
        UUID peerId = UUID.randomUUID();

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "TeamRevoker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Seed with 6 diamonds
                    f.runtime.teamCoordinator().stockLedger().addStock("minecraft:diamond", 6);
                    SharedTask task1 = SharedTask.open(t1, projId, "Craft Diamond Pickaxes", "native_wait",
                            Map.of("reserved_item", "minecraft:diamond", "reserved_count", "6"), Set.of());
                    SharedProject project = SharedProject.create(projId, "Diamond Tools", "Deep mining preparation").withTask(task1);
                    f.runtime.teamCoordinator().registerProject(project);

                    // Native player claims Task 1 and reserves all 6 diamonds
                    var claim1 = f.runtime.teamCoordinator().claimTask(t1, f.id, 0, 1200);
                    context.assertTrue(claim1.isPresent(), "Native player claims Task 1");
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().unreservedCount("minecraft:diamond") == 0, "0 diamonds unreserved");

                    // Native player stops via runtime command (tests halt -> teamCoordinator.handlePlayerStop)
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);

                    // Verify task is revoked and diamonds are released back to unreserved pool
                    var revokedTask = f.runtime.teamCoordinator().findTask(t1).orElseThrow();
                    context.assertTrue(revokedTask.state() == SharedTask.TaskState.REVOKED, "Task transitioned to REVOKED upon stop");
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().unreservedCount("minecraft:diamond") == 6, "All 6 diamonds released");

                    // Peer can now claim the task
                    var claim2 = f.runtime.teamCoordinator().claimTask(t1, peerId, 5, 1200);
                    context.assertTrue(claim2.isPresent(), "Peer successfully claims the revoked task");
                    context.assertTrue(peerId.equals(claim2.get().assignedPlayerId()), "Peer is the new assignee");
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().unreservedCount("minecraft:diamond") == 0, "Diamonds reserved for peer");

                    // Peer dies -> test death revocation as well
                    f.runtime.teamCoordinator().handlePlayerDeath(peerId);
                    var deadTask = f.runtime.teamCoordinator().findTask(t1).orElseThrow();
                    context.assertTrue(deadTask.state() == SharedTask.TaskState.REVOKED, "Task revoked on player death");
                    context.assertTrue(f.runtime.teamCoordinator().stockLedger().unreservedCount("minecraft:diamond") == 6, "Diamonds released on player death");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
