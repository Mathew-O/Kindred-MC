package dev.kindred.core.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the goal a player works toward: its own when it has one, "none" included when that was asked
 * for; in a faction world without one, the survival progression (the dragon where the fight is allowed, else the End
 * portal); and nowhere else, nor without world edits.
 */
class StandingGoalTest {
    private static final SurvivalGoal N = SurvivalGoal.NONE;

    @Test
    void aGoalOfItsOwnStandsInAnyWorld() {
        for (boolean faction : new boolean[] {false, true}) for (boolean dragon : new boolean[] {false, true}) {
            assertEquals(SurvivalGoal.NETHER_PORTAL, StandingGoal.of(SurvivalGoal.NETHER_PORTAL, true, faction, true, dragon));
            assertEquals(SurvivalGoal.STRONGHOLD, StandingGoal.of(SurvivalGoal.STRONGHOLD, true, faction, true, dragon));
        }
    }

    @Test
    void withoutOneInAFactionWorldItTakesUpTheProgressionToTheDragonWhereTheFightIsAllowed() {
        assertEquals(SurvivalGoal.DRAGON, StandingGoal.of(N, false, true, true, true));
        assertEquals(SurvivalGoal.STRONGHOLD, StandingGoal.of(N, false, true, true, false), "the fight off: the End portal");
        assertEquals(SurvivalGoal.DRAGON, StandingGoal.of(null, false, true, true, true), "an unset goal is none");
    }

    @Test
    void outsideAFactionWorldWithoutWorldEditsOrWithNoneAskedForItHasNone() {
        assertEquals(N, StandingGoal.of(N, false, false, true, true), "not a faction world");
        assertEquals(N, StandingGoal.of(N, false, true, false, true), "world edits off: no goal, as a command could set none");
        assertEquals(N, StandingGoal.of(N, true, true, true, true), "none asked for wins");
    }
}
