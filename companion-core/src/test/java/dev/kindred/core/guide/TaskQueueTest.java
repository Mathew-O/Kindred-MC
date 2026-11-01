package dev.kindred.core.guide;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the list of extra things an adviser can ask a player to get: only known items and
 * goals are accepted, amounts and the list's length are capped, a newer ask replaces an older one for the
 * same thing, asks drop off once met or once their goal is behind it, and the player can report what is still
 * to do.
 */
class TaskQueueTest {
    private static final Set<Objective> AHEAD = Set.of(Objective.NETHER_TRIP, Objective.END_FIGHT, Objective.SURVIVAL);

    @Test
    void aModelsWantIsParsedFromFixedSetsOnly() {
        var want = Want.parse(" arrows ", 500, "end_fight", "More for misses\nand the dragon").orElseThrow();
        assertEquals(Need.ARROWS, want.need());
        assertEquals(64, want.count(), "held to the need's cap");
        assertEquals("More for misses and the dragon", want.why());
        assertEquals(1, Want.parse("bow", -3, "END_FIGHT", "").orElseThrow().count());
        assertTrue(Want.parse("diamond_sword", 1, "END_FIGHT", "").isEmpty(), "not a need the planner can act on");
        assertTrue(Want.parse("bow", 1, "raid_the_village", "").isEmpty(), "not a known objective");
        assertTrue(Want.parse(null, 1, null, null).isEmpty());
    }

    @Test
    void advisedWantsAreCappedAndTheSameNeedReplaced() {
        var queue = TaskQueue.EMPTY.advise(List.of(
                new Want(Need.SHIELD, 1, Objective.NETHER_TRIP, "fireballs"),
                new Want(Need.ARROWS, 48, Objective.END_FIGHT, "misses"),
                new Want(Need.FOOD, 24, Objective.SURVIVAL, "hungry nights"),
                new Want(Need.STRING, 6, Objective.END_FIGHT, "a spare bow")));
        assertEquals(TaskQueue.MAX_PER_ADVICE, queue.advised().size(), "three a reply");
        queue = queue.advise(List.of(new Want(Need.ARROWS, 64, Objective.END_FIGHT, "more")));
        assertEquals(3, queue.advised().size());
        assertEquals(64, queue.advised().getLast().count(), "the newer want for arrows replaced the older one");
        for (int i = 0; i < 3; i++) {
            queue = queue.advise(List.of(new Want(Need.values()[4 + i], 2, Objective.SURVIVAL, "")));
        }
        assertEquals(TaskQueue.MAX_ADVISED, queue.advised().size());
        assertEquals(Need.ARROWS, queue.advised().get(2).need());
    }

    @Test
    void wantsGoWhenMetOrWhenTheirObjectiveIsBehind() {
        var queue = TaskQueue.EMPTY.advise(List.of(
                new Want(Need.SHIELD, 1, Objective.NETHER_TRIP, "fireballs"),
                new Want(Need.FOOD, 24, Objective.SURVIVAL, "")));
        assertEquals(2, queue.settle(Map.of(), AHEAD).advised().size());
        assertEquals(List.of(Need.FOOD), queue.settle(Map.of(Need.SHIELD, 1), AHEAD).advised().stream().map(Want::need).toList());
        var pastTheNether = Set.of(Objective.END_FIGHT, Objective.SURVIVAL);
        assertEquals(List.of(Need.FOOD), queue.settle(Map.of(), pastTheNether).advised().stream().map(Want::need).toList());
    }

    @Test
    void anAdvisedWantRaisesWhatTheAssessmentAsksFor() {
        var queue = TaskQueue.EMPTY.advise(List.of(new Want(Need.ARROWS, 64, Objective.END_FIGHT, "misses")));
        var chunks = queue.chunks(Set.of(Objective.END_FIGHT), Map.of(Need.BOW, 1));
        var arrows = chunks.stream().filter(c -> c.need() == Need.ARROWS).findFirst().orElseThrow();
        assertEquals(64, arrows.count(), "the larger of the requirement (32) and the advice (64)");
        assertEquals(16, chunks.stream().filter(c -> c.need() == Need.FLINT).findFirst().orElseThrow().count());
    }

    @Test
    void theReportSaysWhatIsWantedWhatIsHeldAndWhatIsLeft() {
        var queue = TaskQueue.EMPTY.advise(List.of(new Want(Need.SHIELD, 1, Objective.END_FIGHT, "skeletons")));
        String text = queue.describe(Set.of(Objective.END_FIGHT), Map.of(Need.STRING, 2, Need.ARROWS, 4));
        assertTrue(text.contains("bow 0 of 1 (the End fight: to shoot the End crystals"), text);
        assertTrue(text.contains("arrow 4 of 32"), text);
        assertTrue(text.contains("shield 0 of 1 (the End fight: skeletons; queued by you)"), text);
        assertTrue(text.contains("Still to do:") && text.contains("gather string 1,"), text);
        assertTrue(text.contains("craft bow 1"), text);
        assertEquals("Nothing queued for what is ahead.", TaskQueue.EMPTY.describe(Set.of(Objective.STRONGHOLD), Map.of()));
    }
}
