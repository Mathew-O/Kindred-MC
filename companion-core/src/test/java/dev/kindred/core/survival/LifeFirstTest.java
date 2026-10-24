package dev.kindred.core.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class LifeFirstTest {
    private static LifeFirst.State state(int hunger, int carried, boolean night, boolean bed, boolean getting, boolean sought) {
        return new LifeFirst.State(hunger, carried, night, bed, getting, sought);
    }

    @Test
    void wellFedByDayNothingComesBeforeTheBuild() {
        var d = LifeFirst.next(state(20, 0, false, true, false, false));
        assertEquals(Optional.empty(), d.need());
        // Not yet hungry: a player with little food does not drop its build for it.
        assertEquals(Optional.empty(), LifeFirst.next(state(15, 0, false, true, false, false)).need());
    }

    @Test
    void hungryWithTooLittleItGetsFoodAndKeepsOnUntilEnoughToLast() {
        var d = LifeFirst.next(state(14, 5, false, true, false, false));
        assertEquals(Optional.of(LifeFirst.Need.FOOD), d.need());
        assertTrue(d.weight() > LifeFirst.BUILD, "Food above the build");
        // Out getting it, it keeps on past the threshold until a full bar and the reserve are in hand.
        assertEquals(Optional.of(LifeFirst.Need.FOOD), LifeFirst.next(state(18, 10, false, true, true, false)).need());
        assertEquals(Optional.empty(), LifeFirst.next(state(20, 12, false, true, true, false)).need());
        // Eating moves food from pack to body: the same in all.
        assertEquals(LifeFirst.enough(8, 24), LifeFirst.enough(20, 12));
    }

    @Test
    void hungryButWellStockedItEatsWhatItHasAndBuildsOn() {
        assertEquals(Optional.empty(), LifeFirst.next(state(10, 30, false, true, false, false)).need());
    }

    @Test
    void foodLookedForInVainLetsTheBuildGoOn() {
        assertEquals(Optional.empty(), LifeFirst.next(state(10, 0, false, true, true, true)).need());
    }

    @Test
    void atNightItSleepsInItsOwnBedFirstEvenHungry() {
        var d = LifeFirst.next(state(10, 0, true, true, false, false));
        assertEquals(Optional.of(LifeFirst.Need.SLEEP), d.need());
        assertTrue(d.weight() > LifeFirst.FOOD && LifeFirst.FOOD > LifeFirst.BUILD, "Sleep, then food, then the build");
        // With no bed of its own to go to, the night is no reason to stop: hunger still is.
        assertEquals(Optional.of(LifeFirst.Need.FOOD), LifeFirst.next(state(10, 0, true, false, false, false)).need());
        assertEquals(Optional.empty(), LifeFirst.next(state(20, 20, true, false, false, false)).need());
    }
}
