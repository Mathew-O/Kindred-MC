package dev.kindred.core.survival;

import dev.kindred.core.survival.StrategyAdvice.Place;
import dev.kindred.core.survival.StrategyAdvice.Stance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: checks that advice from an optional adviser is kept only when it is sound: an unknown
 * stance is no advice at all, places to avoid must be near what the player reported, text is cut to one short
 * line, a new habit needs a note saying why, and unknown choices are dropped.
 */
class StrategyAdviceTest {

    private static final List<Place> REPORTED = List.of(new Place(35, 32, 87), new Place(-5, 65, 97));

    @Test
    void aKnownStanceIsAcceptedInAnyCase() {
        var advice = StrategyAdvice.validated(" play_safe ", List.of(), "Caves near spawn are deadly", "Two deaths there", REPORTED);
        assertTrue(advice.isPresent());
        assertEquals(Stance.PLAY_SAFE, advice.get().stance());
        assertEquals("Caves near spawn are deadly", advice.get().note());
    }

    @Test
    void anUnknownOrMissingStanceIsNoAdviceAtAll() {
        assertTrue(StrategyAdvice.validated("teleport_home", List.of(), "", "", REPORTED).isEmpty());
        assertTrue(StrategyAdvice.validated(null, List.of(), "", "", REPORTED).isEmpty());
    }

    @Test
    void onlyPlacesNearWhatThePlayerReportedAreKept() {
        var advice = StrategyAdvice.validated("PLAY_SAFE", List.of(new Place(40, 30, 90), new Place(500, 12, -300)), "", "", REPORTED).orElseThrow();
        assertEquals(List.of(new Place(40, 30, 90)), advice.avoid(), "a place the player never reported near is dropped");
    }

    @Test
    void atMostThreePlacesAreKept() {
        var many = List.of(new Place(35, 32, 87), new Place(36, 32, 87), new Place(37, 32, 87), new Place(38, 32, 87));
        assertEquals(StrategyAdvice.MAX_AVOID, StrategyAdvice.validated("PRESS_ON", many, "", "", REPORTED).orElseThrow().avoid().size());
    }

    @Test
    void textIsTrimmedToOnePlainLine() {
        String longText = "a".repeat(500);
        var advice = StrategyAdvice.validated("SURFACE_AND_RECOVER", null, "line one\nline two", longText, REPORTED).orElseThrow();
        assertEquals("line one line two", advice.note());
        assertEquals(StrategyAdvice.MAX_TEXT, advice.reason().length());
        assertTrue(advice.avoid().isEmpty());
    }

    @Test
    void aFocusAndAHabitWithItsNoteAreKept() {
        var advice = StrategyAdvice.validated("PRESS_ON", List.of(), "Two lives ended hungry below; carry more food", "Out of food again",
                REPORTED, "food", "MORE_FOOD", "no_caves").orElseThrow();
        assertEquals(PlanPreferences.Focus.FOOD, advice.focus());
        assertEquals(PlanPreferences.Habit.MORE_FOOD, advice.habit());
        assertEquals(PlanPreferences.Habit.NO_CAVES, advice.retire());
    }

    @Test
    void aHabitNeedsANoteSayingWhy() {
        var advice = StrategyAdvice.validated("PRESS_ON", List.of(), "  ", "", REPORTED, "NONE", "NO_CAVES", null).orElseThrow();
        assertEquals(PlanPreferences.Habit.NONE, advice.habit(), "a habit with no note behind it is not learned");
    }

    @Test
    void anUnknownFocusOrHabitIsNone() {
        var advice = StrategyAdvice.validated("PLAY_SAFE", List.of(), "note", "", REPORTED, "diamonds_now", "teleport", "everything").orElseThrow();
        assertEquals(PlanPreferences.Focus.NONE, advice.focus());
        assertEquals(PlanPreferences.Habit.NONE, advice.habit());
        assertEquals(PlanPreferences.Habit.NONE, advice.retire());
    }

    @Test
    void anotherStanceKeepsTheRest() {
        var advice = StrategyAdvice.validated("SURFACE_AND_RECOVER", List.of(new Place(35, 32, 87)), "note", "why", REPORTED, "WOOD", null, null)
                .orElseThrow().withStance(Stance.PLAY_SAFE);
        assertEquals(Stance.PLAY_SAFE, advice.stance());
        assertEquals(PlanPreferences.Focus.WOOD, advice.focus());
        assertEquals(1, advice.avoid().size());
    }

    @Test
    void followUpsForTheQueueAreKeptOneANeedAndThreeAtMost() {
        var wants = java.util.stream.Stream.of(
                        dev.kindred.core.guide.Want.parse("BOW", 1, "END_FIGHT", "crystals"),
                        dev.kindred.core.guide.Want.parse("BOW", 1, "END_FIGHT", "again"),
                        dev.kindred.core.guide.Want.parse("ARROWS", 48, "END_FIGHT", ""),
                        dev.kindred.core.guide.Want.parse("SHIELD", 1, "NETHER_TRIP", "ghasts"),
                        dev.kindred.core.guide.Want.parse("FOOD", 20, "SURVIVAL", ""))
                .flatMap(java.util.Optional::stream).toList();
        var advice = StrategyAdvice.validated("PRESS_ON", List.of(), "", "", REPORTED, "NONE", "NONE", "NONE", wants).orElseThrow();
        assertEquals(List.of("BOW", "ARROWS", "SHIELD"), advice.queue().stream().map(w -> w.need().name()).toList());
        assertEquals("crystals", advice.queue().getFirst().why(), "the first want for a need is the one kept");
        assertTrue(StrategyAdvice.validated("PRESS_ON", List.of(), "", "", REPORTED).orElseThrow().queue().isEmpty());
    }

    @Test
    void aWayOfThePlaybookIsAdvisedOnlyByItsNameAndKeptWithAnotherStance() {
        var advice = StrategyAdvice.validated("PRESS_ON", List.of(), "", "", REPORTED, "NONE", "NONE", "NONE", List.of(), "pillar").orElseThrow();
        assertEquals(java.util.Optional.of(dev.kindred.core.guide.Playbook.Approach.PILLAR), advice.advised());
        assertEquals(advice.advised(), advice.withStance(Stance.PLAY_SAFE).advised());
        for (String other : new String[] {"NONE", "teleport", "", null}) {
            assertTrue(StrategyAdvice.validated("PRESS_ON", List.of(), "", "", REPORTED, "NONE", "NONE", "NONE", List.of(), other)
                    .orElseThrow().advised().isEmpty(), String.valueOf(other));
        }
        var preferences = new PlanPreferences(java.util.Set.of(), PlanPreferences.Focus.NONE, dev.kindred.core.guide.Playbook.Mode.NORMAL,
                advice.approach());
        assertEquals(advice.advised(), preferences.recommended(), "what the planner is given");
    }
}
