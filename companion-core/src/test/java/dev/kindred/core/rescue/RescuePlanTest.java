package dev.kindred.core.rescue;

import dev.kindred.core.ProposalGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RescuePlanTest {
    private static final Set<String> INSTALLED = Set.of("native_goto", "native_break", "native_pillar", "native_place", "native_wait",
            "native_eat", "native_retreat");

    private static RescuePlan plan(String... steps) {
        var parsed = RescuePlan.parse(List.of(steps), "because");
        assertTrue(parsed.plan().isPresent(), "Refused: " + parsed.refusals());
        return parsed.plan().get();
    }

    private static List<String> refused(String... steps) {
        var parsed = RescuePlan.parse(List.of(steps), "because");
        assertFalse(parsed.plan().isPresent(), "Accepted: " + parsed.plan());
        return parsed.refusals();
    }

    @Test
    void theStubsPlanParsesToItsStepsWithTheirSkills() {
        var plan = plan("pillar 3", "walk north 2");
        assertEquals(List.of(new RescuePlan.Step(RescuePlan.Verb.PILLAR, null, 3), new RescuePlan.Step(RescuePlan.Verb.WALK, RescuePlan.Way.NORTH, 2)),
                plan.steps());
        assertEquals(List.of("native_pillar", "native_goto"), plan.skills());
        assertEquals("pillar 3, walk north 2", plan.text());
        assertEquals("because", plan.reason());
    }

    @Test
    void everyVerbOfTheVocabularyParsesAtItsBoundsAndWritesItselfBackTheSame() {
        String[] steps = {"walk east 8", "dig up 4", "pillar 6", "place west", "wait 4", "eat"};
        var plan = plan(steps);
        assertEquals(List.of(steps), plan.steps().stream().map(RescuePlan.Step::text).toList());
        var rest = plan("retreat west 3", "dig down 1", "walk south 1", "place north", "wait 1");
        assertEquals(List.of("native_retreat", "native_break", "native_goto", "native_place", "native_wait"), rest.skills());
        assertEquals("walk north 2", plan("  Walk   NORTH 2 ").steps().getFirst().text(), "Case and spacing are the model's; the words are not");
    }

    @Test
    void sevenStepsOrNoneAreRefused() {
        assertEquals(List.of("a plan has 1..6 steps, not 7"), refused("wait 1", "wait 1", "wait 1", "wait 1", "wait 1", "wait 1", "wait 1"));
        assertEquals(List.of("a plan has 1..6 steps, not 0"), RescuePlan.parse(List.of(), "").refusals());
        assertEquals(List.of("a plan has 1..6 steps, not 0"), RescuePlan.parse(null, "").refusals());
    }

    @Test
    void anUnknownStepRefusesTheWholePlanAndIsNamed() {
        assertEquals(List.of("unknown step 'fly up 3'"), refused("pillar 3", "fly up 3"));
        assertEquals(List.of("unknown step '/tp @s ~ ~10 ~'"), refused("/tp @s ~ ~10 ~"), "A command is no step");
        assertEquals(List.of("unknown step ''"), refused(""));
    }

    @Test
    void numbersAndWaysOutsideTheirBoundsAreRefused() {
        assertEquals(List.of("'walk north 9': 9 is outside 1..8"), refused("walk north 9"));
        assertEquals(List.of("'walk up 2': a side is north, south, east or west, not up"), refused("walk up 2"));
        assertEquals(List.of("'dig east 5': 5 is outside 1..4"), refused("dig east 5"));
        assertEquals(List.of("'pillar 0': 0 is outside 1..6"), refused("pillar 0"));
        assertEquals(List.of("'pillar 7': 7 is outside 1..6"), refused("pillar 7"));
        assertEquals(List.of("'wait 5': 5 is outside 1..4"), refused("wait 5"));
        assertEquals(List.of("'retreat north 4': 4 is outside 1..3"), refused("retreat north 4"));
        assertEquals(List.of("'place down': a side is north, south, east or west, not down"), refused("place down"));
        assertEquals(List.of("unknown step 'climb_out'"), refused("climb_out"), "The goal's own climb is among the local tries");
        assertEquals(List.of("'walk north two': 'two' is not a number"), refused("walk north two"));
        assertEquals(List.of("'walk sideways 2': 'sideways' is not a way"), refused("walk sideways 2"));
        assertEquals(List.of("'walk north': the step's words are not as the vocabulary writes them"), refused("walk north"));
        assertEquals(List.of("'pillar 3 now': the step's words are not as the vocabulary writes them"), refused("pillar 3 now"));
        assertEquals(List.of("'eat bread': eat takes nothing more"), refused("eat bread"));
        assertEquals(2, refused("walk north 9", "dig east 5").size(), "Each bad step is named");
    }

    @Test
    void aLongReasonIsCutAndItsSpacesClosedUp() {
        var plan = RescuePlan.parse(List.of("eat"), "  out \n of   food " + "x".repeat(400)).plan().orElseThrow();
        assertEquals(RescuePlan.MAX_REASON, plan.reason().length());
        assertTrue(plan.reason().startsWith("out of food x"));
    }

    @Test
    void theGateRefusesAPlanUsingASkillThatIsNotInstalledOrWhoseFenceIsStale() {
        var plan = plan("pillar 3", "retreat east 2");
        assertEquals(List.of(), new ProposalGate(INSTALLED).reject(plan.skills(), true, true, false));
        var without = new java.util.HashSet<>(INSTALLED);
        without.remove("native_retreat");
        assertEquals(List.of("unknown skill native_retreat"), new ProposalGate(without).reject(plan.skills(), true, true, false));
        assertEquals(List.of("the proposal's revisions are stale"), new ProposalGate(INSTALLED).reject(plan.skills(), true, false, false));
    }

    @Test
    void everyVerbIsCarriedOutByASkillTheGameInstalls() {
        for (RescuePlan.Verb verb : RescuePlan.Verb.values()) assertTrue(INSTALLED.contains(RescuePlan.skill(verb)), verb.name());
    }
}
