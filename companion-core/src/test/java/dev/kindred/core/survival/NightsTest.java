package dev.kindred.core.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.guide.Need;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks a player's nights (E-70a): it turns for home in time to be in bed at the first moment, sleeps
 * in the bed it carries when home is too far, holes up and wants a bed when it has none, goes to bed when a person
 * does, stays up only for night work, and carries on outside the Overworld.
 */
class NightsTest {
    private static final double NONE = -1;

    private static Nights.State at(long time, double toHome, boolean carries) {
        return new Nights.State(time, false, true, false, toHome, 4.0, carries, false, false, true);
    }

    @Test
    void itTurnsForHomeSoThatItIsInBedAtTheFirstMoment() {
        // 400 blocks at 4 a second: 100 s, 2,000 ticks; with the half-minute margin, 2,600 before 12,542.
        assertEquals(Nights.SLEEP_FROM - 2_000 - Nights.MARGIN, Nights.turnForHomeAt(400, 4.0));
        // A farther walk turns earlier; a walk over three minutes is out of reach for tonight.
        assertTrue(Nights.turnForHomeAt(600, 4.0) < Nights.turnForHomeAt(400, 4.0));
        assertEquals(-1, Nights.turnForHomeAt(800, 4.0));
        assertEquals(-1, Nights.turnForHomeAt(NONE, 4.0));
        // No pace known yet: a walking pace.
        assertEquals(Nights.walk(43, Nights.WALKING), Nights.walk(43, 0));
    }

    @Test
    void homeInReachItWorksOnUntilItsTurnThenGoesHomeToBed() {
        long turn = Nights.turnForHomeAt(400, 4.0);
        assertEquals(Nights.Plan.NONE, Nights.decide(at(turn - 1, 400, false)).plan());
        var going = Nights.decide(at(turn, 400, false));
        assertEquals(Nights.Plan.HOME, going.plan());
        assertTrue(going.weight() > LifeFirst.BUILD, "Before its work");
        assertEquals(Nights.Plan.HOME, Nights.decide(at(Nights.SLEEP_FROM, 400, false)).plan());
        // Morning: nothing about the night.
        assertEquals(Nights.Plan.NONE, Nights.decide(at(Nights.SLEEP_UNTIL + 1, 400, false)).plan());
    }

    @Test
    void homeOutOfReachItSleepsInTheBedItCarries() {
        // Not before dusk: it beds down where it is when sleeping becomes possible.
        assertEquals(Nights.Plan.NONE, Nights.decide(at(11_000, 2_000, true)).plan());
        var d = Nights.decide(at(Nights.SLEEP_FROM, 2_000, true));
        assertEquals(Nights.Plan.CARRIED, d.plan());
        assertFalse(d.bedUrgent());
        assertEquals(Nights.Plan.CARRIED, Nights.decide(at(18_000, NONE, true)).plan());
    }

    @Test
    void noBedItHolesUpAndWantsABedFirstThing() {
        var d = Nights.decide(at(18_000, NONE, false));
        assertEquals(Nights.Plan.HOLE_UP, d.plan());
        assertTrue(d.bedUrgent());
        assertEquals(Optional.of(Need.BED), Nights.raises(d));
        assertTrue(d.weight() < Nights.BED && d.weight() > LifeFirst.FOOD, "Its own bed before a hole-up; both before food");
    }

    @Test
    void whenAPersonLiesDownItGoesToBedTooAtOnce() {
        var asleep = new Nights.State(14_000, false, true, false, 400, 4.0, false, false, true, true);
        var d = Nights.decide(asleep);
        assertEquals(Nights.Plan.HOME, d.plan());
        assertTrue(d.weight() > Nights.BED, "Above its own night");
        var far = new Nights.State(14_000, false, true, false, NONE, 4.0, true, false, true, true);
        assertEquals(Nights.Plan.CARRIED, Nights.decide(far).plan());
        // One that cannot be in bed (in a fight) is left out of the count, never holding up the person's night.
        var fighting = new Nights.State(14_000, false, true, false, 400, 4.0, false, false, true, false);
        assertTrue(Nights.decide(fighting).weight() < Nights.WITH_A_PERSON);
        assertTrue(Nights.leftOutOfSleepCount(true, false, false));
        assertFalse(Nights.leftOutOfSleepCount(true, true, false), "One in bed is counted");
        assertFalse(Nights.leftOutOfSleepCount(true, false, true), "aiPlayersCountForSleep counts them all");
        assertFalse(Nights.leftOutOfSleepCount(false, false, false), "The rule for people is unchanged");
    }

    @Test
    void itStaysUpOnlyForNightWorkAndSaysSo() {
        var work = new Nights.State(18_000, false, true, false, 400, 4.0, true, true, false, true);
        var d = Nights.decide(work);
        assertEquals(Nights.Plan.NIGHT_WORK, d.plan());
        assertTrue(d.why().contains("night"));
        // Night work keeps it up even when a person lies down.
        var both = new Nights.State(18_000, false, true, false, 400, 4.0, true, true, true, true);
        assertEquals(Nights.Plan.NIGHT_WORK, Nights.decide(both).plan());
    }

    @Test
    void outsideTheOverworldAndInAMatchWithItsOwnRulesItCarriesOn() {
        var nether = new Nights.State(18_000, false, false, false, 400, 4.0, true, false, true, true);
        assertEquals(Nights.Plan.NONE, Nights.decide(nether).plan());
        var speedrun = new Nights.State(18_000, false, true, true, 400, 4.0, true, false, true, true);
        assertEquals(Nights.Plan.NONE, Nights.decide(speedrun).plan());
    }

    @Test
    void aStormIsANightForBeds() {
        assertTrue(Nights.sleepTime(6_000, true));
        assertFalse(Nights.sleepTime(6_000, false));
        assertEquals(Nights.Plan.HOME, Nights.decide(new Nights.State(6_000, true, true, false, 40, 4.0, false, false, false, true)).plan());
    }
}
