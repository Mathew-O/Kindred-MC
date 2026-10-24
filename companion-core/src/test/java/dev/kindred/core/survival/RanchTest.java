package dev.kindred.core.survival;

import dev.kindred.core.construction.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RanchTest {
    private static final UUID DAISY = new UUID(0, 1), BESS = new UUID(0, 2), CALF = new UUID(0, 3);

    /** A seven-by-seven pen from (0, 64, 0): its gate at (3, 64, 6), inside x and z 1 to 5. */
    private static Ranch ranch() {
        return new Ranch(new Pos(0, 64, 0), 7, 7, Ranch.Kind.COW);
    }

    private static Ranch.Animal cow(UUID id, int x, int z) {
        return new Ranch.Animal(id, Ranch.Kind.COW, new Pos(x, 64, z), true, true, false);
    }

    private static Ranch.Observation seen(int x, int z, boolean gateOpen, int wheat, long tick, Ranch.Animal... animals) {
        return new Ranch.Observation(new Pos(x, 64, z), true, gateOpen, wheat, List.of(animals), tick);
    }

    @Test
    void itBuildsThePenThenLeadsAPairInShutsThemInBreedsThemAndShutsTheGateBehind() {
        var ranch = ranch();
        assertInstanceOf(Ranch.Step.Build.class, ranch.next(new Ranch.Observation(new Pos(3, 64, 9), false, false, 8, List.of(), 0)));

        // Out to the pair, wheat in hand.
        var walk = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 9, false, 8, 10, cow(DAISY, 15, 10), cow(BESS, 16, 11))));
        assertEquals(new Pos(15, 64, 10), walk.target());
        assertTrue(walk.holdFood());
        // Following: led back to the gate a few blocks at a time, waited for when it falls behind.
        var lead = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(13, 10, false, 8, 20, cow(DAISY, 11, 10), cow(BESS, 12, 11))));
        assertEquals(new Pos(3, 64, 10), lead.target(), "round to the front of the gate, to come at it in line");
        assertTrue(lead.holdFood() && lead.leg() > 0, "a short leg at the pace of the animal behind");
        assertInstanceOf(Ranch.Step.Wait.class, ranch.next(seen(8, 10, false, 8, 30, cow(DAISY, 13, 10), cow(BESS, 13, 11))));
        // Lined up in front: to the gate, which is opened, then across the pen for them to follow in.
        var lined = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 10, false, 8, 35, cow(DAISY, 5, 11), cow(BESS, 6, 12))));
        assertEquals(new Pos(3, 64, 7), lined.target(), "to just outside the gate");
        assertEquals(new Ranch.Step.Gate(true, "Open the gate"), ranch.next(seen(3, 7, false, 8, 40, cow(DAISY, 5, 8), cow(BESS, 6, 9))));
        var in = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 7, true, 8, 50, cow(DAISY, 5, 8), cow(BESS, 6, 9))));
        assertEquals(new Pos(3, 64, 1), in.target());
        assertInstanceOf(Ranch.Step.Wait.class, ranch.next(seen(3, 1, true, 8, 60, cow(DAISY, 3, 4), cow(BESS, 3, 7))), "waits for them to come in");

        // Both in: the food put away, the gate shut on them from inside.
        var toGate = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 1, true, 8, 70, cow(DAISY, 3, 3), cow(BESS, 4, 4))));
        assertEquals(new Pos(3, 64, 5), toGate.target());
        assertFalse(toGate.holdFood(), "nothing follows it to the gate");
        assertInstanceOf(Ranch.Step.Gate.class, ranch.next(seen(3, 5, true, 8, 80, cow(DAISY, 3, 3), cow(BESS, 4, 4))));

        // Fed one after the other, each walked up to.
        assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 5, false, 8, 90, cow(DAISY, 3, 1), cow(BESS, 5, 1))));
        assertEquals(DAISY, assertInstanceOf(Ranch.Step.Feed.class, ranch.next(seen(3, 3, false, 8, 100, cow(DAISY, 3, 1), cow(BESS, 5, 1)))).animal());
        var daisyInLove = new Ranch.Animal(DAISY, Ranch.Kind.COW, new Pos(3, 64, 1), true, false, true);
        assertEquals(BESS, assertInstanceOf(Ranch.Step.Feed.class, ranch.next(seen(4, 2, false, 7, 110, daisyInLove, cow(BESS, 5, 1)))).animal());
        var bessInLove = new Ranch.Animal(BESS, Ranch.Kind.COW, new Pos(5, 64, 1), true, false, true);
        assertInstanceOf(Ranch.Step.Wait.class, ranch.next(seen(4, 2, false, 6, 120, daisyInLove, bessInLove)), "waits for the young one");

        // A calf: out through the gate, and the gate shut behind.
        var calf = new Ranch.Animal(CALF, Ranch.Kind.COW, new Pos(4, 64, 1), false, false, false);
        var cooling = List.of(new Ranch.Animal(DAISY, Ranch.Kind.COW, new Pos(3, 64, 1), true, false, false),
                new Ranch.Animal(BESS, Ranch.Kind.COW, new Pos(5, 64, 1), true, false, false), calf);
        var toLeave = assertInstanceOf(Ranch.Step.Go.class, ranch.next(new Ranch.Observation(new Pos(4, 64, 2), true, false, 6, cooling, 180)));
        assertEquals(new Pos(3, 64, 5), toLeave.target());
        assertEquals(new Ranch.Step.Gate(true, "Open the gate to leave"), ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, false, 6, cooling, 190)));
        var out = assertInstanceOf(Ranch.Step.Go.class, ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, true, 6, cooling, 200)));
        assertEquals(new Pos(3, 64, 7), out.target(), "one step out");
        assertFalse(out.holdFood());
        assertEquals(new Ranch.Step.Gate(false, "Shut the gate behind"), ranch.next(new Ranch.Observation(new Pos(3, 64, 7), true, true, 6, cooling, 210)));
        var done = assertInstanceOf(Ranch.Step.Done.class, ranch.next(new Ranch.Observation(new Pos(3, 64, 7), true, false, 6, cooling, 220)));
        assertTrue(done.bred());
        assertEquals("A young cow born in the pen", done.outcome());
        assertTrue(ranch.done());
    }

    @Test
    void withOneInItShutsTheGateOnItBeforeFetchingTheNext() {
        var ranch = ranch();
        // Daisy is in, Bess stayed out far off: out without the food, so Daisy stays.
        var out = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 1, true, 8, 0, cow(DAISY, 3, 3), cow(BESS, 20, 20))));
        assertFalse(out.holdFood());
        assertEquals(new Pos(3, 64, 7), out.target());
        assertEquals(new Ranch.Step.Gate(false, "Shut the gate on the cow inside"), ranch.next(seen(3, 7, true, 8, 10, cow(DAISY, 3, 3), cow(BESS, 20, 20))));
        var fetch = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 7, false, 8, 20, cow(DAISY, 3, 3), cow(BESS, 20, 20))));
        assertEquals(new Pos(20, 64, 20), fetch.target());
        assertTrue(fetch.holdFood());
    }

    @Test
    void oneThatWillNotComeInIsLeftAndWithNoneLeftItSaysSoAndLeaves() {
        var ranch = ranch();
        ranch.next(seen(3, 1, true, 8, 0, cow(DAISY, 3, 3), cow(BESS, 3, 8)));
        // Bess stands just outside, following, and never comes in: drawn from across the pen, then from just inside the gate.
        assertInstanceOf(Ranch.Step.Wait.class, ranch.next(seen(3, 1, true, 8, 30, cow(DAISY, 3, 3), cow(BESS, 3, 7))));
        var toGate = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 1, true, 8, 100, cow(DAISY, 3, 3), cow(BESS, 3, 7))));
        assertEquals(new Pos(3, 64, 5), toGate.target());
        assertTrue(toGate.holdFood());
        assertEquals(new Ranch.Step.Wait(false, "That cow will not come in"), ranch.next(seen(3, 1, true, 8, 400, cow(DAISY, 3, 3), cow(BESS, 3, 7))));
        var leaving = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 1, true, 8, 410, cow(DAISY, 3, 3), cow(BESS, 3, 7))));
        assertFalse(leaving.holdFood());
        ranch.next(seen(3, 7, true, 8, 420, cow(DAISY, 3, 3), cow(BESS, 3, 9)));
        var done = assertInstanceOf(Ranch.Step.Done.class, ranch.next(seen(3, 7, false, 8, 430, cow(DAISY, 3, 3), cow(BESS, 3, 9))));
        assertEquals("Only one cow in the pen; no other in sight", done.outcome());
        assertFalse(done.bred());
    }

    @Test
    void oneHungOnTheFenceBesideTheGateIsBroughtRoundInLineWithItAgainFromOutside() {
        var ranch = ranch();
        ranch.next(seen(3, 1, true, 8, 0, cow(DAISY, 3, 3), cow(BESS, 2, 7)));
        // Bess follows to the fence beside the gate and stays there: drawn from inside a while first.
        assertInstanceOf(Ranch.Step.Wait.class, ranch.next(seen(3, 1, true, 8, 30, cow(DAISY, 3, 3), cow(BESS, 2, 7))));
        // Then out through the gate, the food put away, to bring her round.
        var out = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 1, true, 8, 130, cow(DAISY, 3, 3), cow(BESS, 2, 7))));
        assertEquals(new Pos(3, 64, 7), out.target());
        assertEquals("Out to bring the cow round to the gate", out.why());
        assertFalse(out.holdFood());
        // Outside: round to the front of the gate with the food, for her to come up behind it in line.
        var round = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 7, true, 8, 140, cow(DAISY, 3, 3), cow(BESS, 2, 8))));
        assertEquals(new Pos(3, 64, 10), round.target());
        assertTrue(round.holdFood());
        // In line with the gate, as one that will not come in, she is in the end given up as before.
        var stubborn = ranch();
        stubborn.next(seen(3, 1, true, 8, 0, cow(DAISY, 3, 3), cow(BESS, 3, 7)));
        assertInstanceOf(Ranch.Step.Wait.class, stubborn.next(seen(3, 1, true, 8, 130, cow(DAISY, 3, 3), cow(BESS, 3, 7))));
    }

    @Test
    void oneThatSlipsOutAsItLeavesIsBroughtBackIn() {
        var ranch = ranch();
        var daisy = new Ranch.Animal(DAISY, Ranch.Kind.COW, new Pos(2, 64, 2), true, false, false);
        var bess = new Ranch.Animal(BESS, Ranch.Kind.COW, new Pos(4, 64, 2), true, false, false);
        var calf = new Ranch.Animal(CALF, Ranch.Kind.COW, new Pos(3, 64, 2), false, false, false);
        // Shut in, not ready to breed again: it leaves with all three penned.
        ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, false, 8, List.of(daisy, bess, calf), 0));
        ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, true, 8, List.of(daisy, bess, calf), 10));
        // Out and the gate shut, the calf has slipped out behind it: it turns back for it, wheat in hand.
        var calfOut = new Ranch.Animal(CALF, Ranch.Kind.COW, new Pos(10, 64, 12), false, false, false);
        var back = assertInstanceOf(Ranch.Step.Go.class, ranch.next(new Ranch.Observation(new Pos(3, 64, 7), true, false, 8, List.of(daisy, bess, calfOut), 20)));
        assertTrue(back.holdFood());
        assertEquals("bringing a cow back into the pen", ranch.phase());
        // Led in, the gate shut on them all from inside, then out again.
        var calfIn = new Ranch.Animal(CALF, Ranch.Kind.COW, new Pos(3, 64, 3), false, false, false);
        assertInstanceOf(Ranch.Step.Gate.class, ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, true, 8, List.of(daisy, bess, calfIn), 30)));
        assertInstanceOf(Ranch.Step.Gate.class, ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, false, 8, List.of(daisy, bess, calfIn), 40)));
        assertInstanceOf(Ranch.Step.Go.class, ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, true, 8, List.of(daisy, bess, calfIn), 50)));
        ranch.next(new Ranch.Observation(new Pos(3, 64, 7), true, true, 8, List.of(daisy, bess, calfIn), 60));
        assertInstanceOf(Ranch.Step.Done.class, ranch.next(new Ranch.Observation(new Pos(3, 64, 7), true, false, 8, List.of(daisy, bess, calfIn), 70)));
    }

    @Test
    void itOpensTheGateToLeaveOnlyOnceNoAnimalStandsJustInsideIt() {
        var ranch = ranch();
        var daisy = new Ranch.Animal(DAISY, Ranch.Kind.COW, new Pos(3, 64, 4), true, false, false);
        var bess = new Ranch.Animal(BESS, Ranch.Kind.COW, new Pos(1, 64, 1), true, false, false);
        // Not ready to breed: it leaves, but Daisy stands by the gate inside.
        assertEquals(new Ranch.Step.Wait(false, "Wait for the cows to move off the gate"), ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, false, 8, List.of(daisy, bess), 0)));
        var moved = new Ranch.Animal(DAISY, Ranch.Kind.COW, new Pos(1, 64, 2), true, false, false);
        assertInstanceOf(Ranch.Step.Gate.class, ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, false, 8, List.of(moved, bess), 40)));
        // One that never moves off is not waited on for ever.
        var stays = ranch();
        stays.next(new Ranch.Observation(new Pos(3, 64, 5), true, false, 8, List.of(daisy, bess), 0));
        assertInstanceOf(Ranch.Step.Gate.class, stays.next(new Ranch.Observation(new Pos(3, 64, 5), true, false, 8, List.of(daisy, bess), 300)));
    }

    @Test
    void withNoFoodOrNoAnimalsOrNoneReadyToBreedItStops() {
        assertEquals("No wheat to lead cows with",
                assertInstanceOf(Ranch.Step.Done.class, ranch().next(seen(3, 9, false, 0, 0, cow(DAISY, 15, 10)))).outcome());
        assertEquals("No cows in sight to bring in", assertInstanceOf(Ranch.Step.Done.class, ranch().next(seen(3, 9, false, 8, 0))).outcome());
        // A pig is no cow; a cow far beyond the ranch's range is not looked for.
        var pig = new Ranch.Animal(BESS, Ranch.Kind.PIG, new Pos(10, 64, 10), true, true, false);
        assertInstanceOf(Ranch.Step.Done.class, ranch().next(seen(3, 9, false, 8, 0, pig, cow(DAISY, 80, 80))));

        var ranch = ranch();
        var cooling = new ArrayList<Ranch.Animal>();
        cooling.add(new Ranch.Animal(DAISY, Ranch.Kind.COW, new Pos(3, 64, 2), true, false, false));
        cooling.add(new Ranch.Animal(BESS, Ranch.Kind.COW, new Pos(4, 64, 2), true, false, false));
        ranch.next(new Ranch.Observation(new Pos(3, 64, 4), true, false, 8, cooling, 0));
        assertTrue(ranch.phase().startsWith("leaving") || ranch.done());
        assertThrows(IllegalArgumentException.class, () -> new Ranch(new Pos(0, 0, 0), 6, 7, Ranch.Kind.COW));
    }
}
