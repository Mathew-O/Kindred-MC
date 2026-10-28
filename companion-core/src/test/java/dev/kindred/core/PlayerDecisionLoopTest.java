package dev.kindred.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static dev.kindred.core.PlayerDecisionLoop.*;

/**
 * In plain terms: checks how a player decides what to do next and when to change its mind.
 */
class PlayerDecisionLoopTest {
    private final PlayerIdentity id = new PlayerIdentity(UUID.randomUUID(), UUID.randomUUID(), "Alex");
    private final PlayerDecisionLoop loop = new PlayerDecisionLoop(id);
    private final Personality traits = new Personality(50, 50, 50, 50, 50, 50, 50, 50, 50, 0);
    private final TaskPreferences.Candidate task = new TaskPreferences.Candidate(UUID.randomUUID(),
            TaskPreferences.Priority.PROJECT, 50, TaskPreferences.Affinities.none(),
            new TaskPreferences.Eligibility(true, true, true, true));

    private Frame frame(boolean usable, boolean stopped, boolean skill, boolean follow, boolean lease, boolean admitted) {
        return new Frame(usable, stopped, skill, follow, lease, admitted, null, List.of(task));
    }

    @Test void idlePlayerSelectsLocalTaskWithoutHumanOrMode() {
        Decision decision = loop.decide(frame(true, false, false, false, false, true), traits);
        assertEquals(Action.PROPOSE_TASK, decision.action());
        assertEquals(task.id(), decision.proposal().orElseThrow().candidate().id());
        assertEquals(id, loop.identity());
    }

    @Test void stopAndFaultDominateRunningTasksAndProposals() {
        assertEquals(Action.HALT, loop.decide(frame(true, true, true, true, true, true), traits).action());
        assertEquals(Action.HALT, loop.decide(frame(false, false, true, false, true, true), traits).action());
    }

    @Test void activeSkillsCannotBeOverriddenByFollowOrPlanning() {
        assertEquals(Action.RUN_SKILL, loop.decide(frame(true, false, true, true, true, true), traits).action());
        assertEquals(Action.RUN_FOLLOW, loop.decide(frame(true, false, false, true, true, true), traits).action());
        assertEquals(Action.HALT, loop.decide(frame(true, false, false, true, false, true), traits).action());
    }

    @Test void noAdmissionOrCapabilitiesMeansNoSpeculativeWork() {
        assertEquals(Action.WAIT, loop.decide(frame(true, false, false, false, false, false), traits).action());
        assertEquals(Action.WAIT, loop.decide(new Frame(true, false, false, false, false, true, null, List.of()), traits).action());
        var unavailable = new TaskPreferences.Candidate(UUID.randomUUID(), TaskPreferences.Priority.URGENT,
                100, TaskPreferences.Affinities.none(), new TaskPreferences.Eligibility(true, false, true, true));
        assertEquals(Action.WAIT, loop.decide(new Frame(true, false, false, false, false, true, null, List.of(unavailable)), traits).action());
    }

    @Test void creatorDoesNotFormPartOfPlayerIdentity() {
        Companion saved = Companion.create(id.id(), UUID.randomUUID(), id.name(), id.worldId(), "minecraft:overworld");
        assertEquals(id, saved.playerIdentity());
        assertEquals(id, Companion.create(id.id(), UUID.randomUUID(), id.name(), id.worldId(), "minecraft:overworld").playerIdentity());
    }
}
