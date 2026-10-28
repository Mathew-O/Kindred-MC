package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks a few ground rules together: politeness in doorways, how many AI players may be
 * awake, that model advice can only name real actions, and that removal tokens work once.
 */
class PlayContractsTest {
    @Test
    void courtesyWaitsInADoorwayAndDoesNotSprintThere() {
        assertEquals(Courtesy.Passage.WAIT, Courtesy.passage(true, true, false));
        assertEquals(Courtesy.Passage.STEP_ASIDE, Courtesy.passage(false, false, true));
        assertEquals(Courtesy.Passage.PROCEED, Courtesy.passage(false, false, false));
        assertFalse(Courtesy.sprintAllowed(Courtesy.Passage.WAIT));
        assertTrue(Courtesy.sprintAllowed(Courtesy.Passage.PROCEED));
    }

    @Test
    void admissionKeepsTwoCompanionsAndOnePerOwner() {
        AdmissionBoard board = new AdmissionBoard();
        UUID owner = UUID.randomUUID();
        assertEquals(AdmissionBoard.Decision.ADMIT, board.request(UUID.randomUUID(), owner, 9));
        assertEquals(AdmissionBoard.Decision.WAITING, board.request(UUID.randomUUID(), owner, 4));
        assertEquals(AdmissionBoard.Decision.ADMIT, board.request(UUID.randomUUID(), UUID.randomUUID(), 9));
        assertEquals(AdmissionBoard.Decision.WAITING, board.request(UUID.randomUUID(), UUID.randomUUID(), 1));
        assertEquals(AdmissionBoard.Decision.REJECTED, board.request(UUID.randomUUID(), UUID.randomUUID(), 10));
    }

    @Test
    void proposalsWithoutAProviderAreRejectedAndUnknownSkillsNeverPass() {
        ProposalGate gate = new ProposalGate(Set.of("gather_logs", "deposit"));
        assertFalse(gate.reject(List.of("gather_logs"), false, true, false).isEmpty());
        assertTrue(gate.reject(List.of("gather_logs"), true, true, false).isEmpty());
        assertFalse(gate.reject(List.of("sudo"), true, true, false).isEmpty());
        assertFalse(gate.reject(List.of(), true, true, false).isEmpty());
    }

    @Test
    void removalTokensAreOneShotAndJournalsOnlyCountWhatTheyAreGiven() {
        UUID token = UUID.randomUUID();
        UUID companion = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        RemovalToken issued = new RemovalToken(token, companion, actor, 1_000);
        assertTrue(issued.matches(token, companion, actor, 999));
        assertFalse(issued.matches(token, companion, actor, 1_000));
        assertFalse(issued.matches(UUID.randomUUID(), companion, actor, 1));
        assertTrue(JournalSummary.render("Juniper", 0, 0, 0, List.of()).contains("No verified work"));
        assertTrue(JournalSummary.render("Juniper", 2, 0, 1, List.of("deposited 8 oak_log")).contains("2 verified"));
        GoalRecord goal = new GoalRecord(UUID.randomUUID(), companion, GoalRecord.Origin.OWNER, "Stock the chest", "8 oak logs deposited", 1, GoalRecord.Status.OPEN, 0);
        assertEquals(GoalRecord.Status.DONE, goal.advance(GoalRecord.Status.DONE).status());
        assertEquals(OperatingMode.Mode.INDEPENDENT, OperatingMode.defaults(companion).withMode(OperatingMode.Mode.INDEPENDENT).mode());
        assertThrows(IllegalArgumentException.class, () -> new MemoryNote(UUID.randomUUID(), companion, MemoryNote.Kind.FACT, "x", "", 0));
    }
}
