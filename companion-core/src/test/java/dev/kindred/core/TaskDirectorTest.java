package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskDirectorTest {

    private static final TaskPreferences.Eligibility AVAILABLE = new TaskPreferences.Eligibility(true, true, true, true);
    private static final TaskPreferences.Eligibility UNAVAILABLE = new TaskPreferences.Eligibility(false, true, true, true);

    private Personality personality(int initiative, int farming, int mining, int combat) {
        return new Personality(initiative, 50, 50, 50, 50, combat, 50, farming, mining, 0);
    }

    @Test
    void urgentPrecedesProjectRegardlessOfAffinity() {
        UUID urgentId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        // Urgent has lower base need and 0 farming affinity
        TaskPreferences.Candidate urgent = new TaskPreferences.Candidate(urgentId, TaskPreferences.Priority.URGENT,
                50.0, TaskPreferences.Affinities.none(), AVAILABLE);

        // Project has higher base need and 100% farming affinity for a farming lover
        TaskPreferences.Affinities farmAffinity = new TaskPreferences.Affinities(0, 0, 1.0, 0);
        TaskPreferences.Candidate project = new TaskPreferences.Candidate(projectId, TaskPreferences.Priority.PROJECT,
                90.0, farmAffinity, AVAILABLE);

        Personality farmer = personality(50, 100, 50, 50);
        OperatingMode mode = OperatingMode.defaults(UUID.randomUUID());

        Optional<TaskDirector.Selection> chosen = TaskDirector.select(List.of(urgent, project), null, mode, farmer);
        assertTrue(chosen.isPresent());
        assertEquals(urgentId, chosen.get().candidate().id(), "Urgent task must precede project task");
        assertTrue(chosen.get().rationale().contains("Urgent"));
    }

    @Test
    void personalityBiasSelectsPreferredTaskInSamePriority() {
        UUID farmId = UUID.randomUUID();
        UUID mineId = UUID.randomUUID();

        TaskPreferences.Candidate farm = new TaskPreferences.Candidate(farmId, TaskPreferences.Priority.PROJECT,
                50.0, new TaskPreferences.Affinities(0, 0, 1.0, 0), AVAILABLE);
        TaskPreferences.Candidate mine = new TaskPreferences.Candidate(mineId, TaskPreferences.Priority.PROJECT,
                51.0, new TaskPreferences.Affinities(0, 0, 0, 1.0), AVAILABLE);

        // Farmer (farming 100 gives +5 bias, mining 50 gives 0 bias)
        // farm score = 50 + 5 = 55; mine score = 51 + 0 = 51
        Personality farmer = personality(50, 100, 50, 50);
        OperatingMode mode = OperatingMode.defaults(UUID.randomUUID());

        Optional<TaskDirector.Selection> chosen = TaskDirector.select(List.of(farm, mine), null, mode, farmer);
        assertTrue(chosen.isPresent());
        assertEquals(farmId, chosen.get().candidate().id(), "Farming preference must boost score past close alternative");
    }

    @Test
    void switchingHysteresisRetainsCurrentTaskUnlessNewTaskBeatsByMargin() {
        UUID currentId = UUID.randomUUID();
        UUID rivalId = UUID.randomUUID();

        TaskPreferences.Candidate current = new TaskPreferences.Candidate(currentId, TaskPreferences.Priority.PROJECT,
                50.0, TaskPreferences.Affinities.none(), AVAILABLE);
        // Rival scores 51.5 (+1.5 higher, but less than SWITCH_MARGIN of 3.0)
        TaskPreferences.Candidate rival = new TaskPreferences.Candidate(rivalId, TaskPreferences.Priority.PROJECT,
                51.5, TaskPreferences.Affinities.none(), AVAILABLE);

        Personality neutral = personality(50, 50, 50, 50);
        OperatingMode mode = OperatingMode.defaults(UUID.randomUUID());

        Optional<TaskDirector.Selection> chosen = TaskDirector.select(List.of(current, rival), currentId, mode, neutral);
        assertTrue(chosen.isPresent());
        assertEquals(currentId, chosen.get().candidate().id(), "Current task must be retained if rival does not beat by 3 points");

        // Now test rival scoring 54.0 (+4.0 higher, beats SWITCH_MARGIN of 3.0)
        TaskPreferences.Candidate clearWinner = new TaskPreferences.Candidate(rivalId, TaskPreferences.Priority.PROJECT,
                54.0, TaskPreferences.Affinities.none(), AVAILABLE);

        chosen = TaskDirector.select(List.of(current, clearWinner), currentId, mode, neutral);
        assertTrue(chosen.isPresent());
        assertEquals(rivalId, chosen.get().candidate().id(), "Rival beating current by >3 points triggers a switch");
    }

    @Test
    void lowInitiativeSuppressesRoutineTasks() {
        UUID routineId = UUID.randomUUID();
        TaskPreferences.Candidate routine = new TaskPreferences.Candidate(routineId, TaskPreferences.Priority.ROUTINE,
                60.0, TaskPreferences.Affinities.none(), AVAILABLE);

        Personality passive = personality(10, 50, 50, 50); // initiative = 10 < 20
        OperatingMode mode = OperatingMode.defaults(UUID.randomUUID());

        Optional<TaskDirector.Selection> chosen = TaskDirector.select(List.of(routine), null, mode, passive);
        assertFalse(chosen.isPresent(), "Low initiative must not auto-admit routine tasks");

        Personality active = personality(60, 50, 50, 50); // initiative = 60 >= 20
        chosen = TaskDirector.select(List.of(routine), null, mode, active);
        assertTrue(chosen.isPresent(), "Active companion admits routine tasks");
    }

    @Test
    void unavailableCandidatesAreIgnored() {
        UUID unapprovedId = UUID.randomUUID();
        TaskPreferences.Candidate unapproved = new TaskPreferences.Candidate(unapprovedId, TaskPreferences.Priority.URGENT,
                99.0, TaskPreferences.Affinities.none(), UNAVAILABLE);

        OperatingMode mode = OperatingMode.defaults(UUID.randomUUID());
        Optional<TaskDirector.Selection> chosen = TaskDirector.select(List.of(unapproved), null, mode, personality(50, 50, 50, 50));
        assertFalse(chosen.isPresent(), "Ineligible candidate must never be selected");
    }

    @Test
    void forbiddenBossTargetsAreStrictlyExcluded() {
        UUID bossId = UUID.randomUUID();
        TaskDirector.markBossTarget(bossId);
        TaskPreferences.Candidate boss = new TaskPreferences.Candidate(bossId, TaskPreferences.Priority.URGENT,
                100.0, new TaskPreferences.Affinities(1.0, 0, 0, 0), AVAILABLE);

        Personality combative = personality(100, 50, 50, 100);
        OperatingMode mode = OperatingMode.defaults(UUID.randomUUID());

        Optional<TaskDirector.Selection> chosen = TaskDirector.select(List.of(boss), null, mode, combative);
        assertFalse(chosen.isPresent(), "Major boss combat is strictly excluded from candidate selection");
    }
}
