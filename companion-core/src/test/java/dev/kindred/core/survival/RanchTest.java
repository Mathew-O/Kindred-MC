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
    void oneThatWalksBackInOnItsOwnWhileItIsOutsideIsShutInFromInsideThePen() {
        var ranch = ranch();
        var daisy = new Ranch.Animal(DAISY, Ranch.Kind.COW, new Pos(2, 64, 2), true, false, false);
        var bess = new Ranch.Animal(BESS, Ranch.Kind.COW, new Pos(4, 64, 2), true, false, false);
        var calf = new Ranch.Animal(CALF, Ranch.Kind.COW, new Pos(3, 64, 2), false, false, false);
        ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, false, 8, List.of(daisy, bess, calf), 0));
        ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, true, 8, List.of(daisy, bess, calf), 10));
        var calfOut = new Ranch.Animal(CALF, Ranch.Kind.COW, new Pos(10, 64, 12), false, false, false);
        assertInstanceOf(Ranch.Step.Go.class, ranch.next(new Ranch.Observation(new Pos(3, 64, 7), true, false, 8, List.of(daisy, bess, calfOut), 20)));
        // The calf back in on its own while it was outside: the gate opened, then in past the gateway to shut it.
        var calfIn = new Ranch.Animal(CALF, Ranch.Kind.COW, new Pos(3, 64, 3), false, false, false);
        assertEquals(new Ranch.Step.Gate(true, "Open the gate"),
                ranch.next(new Ranch.Observation(new Pos(3, 64, 7), true, false, 8, List.of(daisy, bess, calfIn), 30)));
        var into = assertInstanceOf(Ranch.Step.Go.class,
                ranch.next(new Ranch.Observation(new Pos(3, 64, 6), true, true, 8, List.of(daisy, bess, calfIn), 40)));
        assertTrue(ranch.inside(into.target()), "in the pen: " + into);
        assertTrue(Math.sqrt(into.target().distanceSquared(ranch.gate())) > into.range(), "standing in the gateway is not in: " + into);
        assertEquals(new Ranch.Step.Gate(false, "Shut the cows in"),
                ranch.next(new Ranch.Observation(new Pos(3, 64, 5), true, true, 8, List.of(daisy, bess, calfIn), 50)));
    }

    @Test
    void walledInItLeadsTheAnimalUpToTheDoorwayInLineThenThroughItWithoutStopping() {
        // The pen in a ward walled at z 12, its doorway at x 3; the spot two out from it at z 14.
        var ranch = new Ranch(new Pos(0, 64, 0), 7, 7, Ranch.Kind.COW, new Pos(3, 64, 14));
        var up = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 20, false, 8, 0, cow(DAISY, 3, 23))));
        assertEquals(new Pos(3, 64, 14), up.target());
        assertTrue(up.holdFood());
        // There, the cow off to one side: it waits for it to come up behind, in line.
        assertInstanceOf(Ranch.Step.Wait.class, ranch.next(seen(3, 14, false, 8, 10, cow(DAISY, 5, 17))));
        // In line behind: through the doorway in one walk, not a leg at a time.
        var through = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 14, false, 8, 20, cow(DAISY, 3, 16))));
        assertEquals(ranch.front(), through.target());
        assertEquals(0, through.leg(), "no stop in the doorway");
        // Inside, the cow following: the gate opened for it, as in the open.
        var gate = ranch.next(seen(3, 10, false, 8, 30, cow(DAISY, 3, 13)));
        assertTrue(gate instanceof Ranch.Step.Gate || gate instanceof Ranch.Step.Go go && go.why().startsWith("To the gate"), "the gate next: " + gate);
        // One that will not come into line is not waited on for ever, and asked again before it has gone, the same walk.
        var stubborn = new Ranch(new Pos(0, 64, 0), 7, 7, Ranch.Kind.COW, new Pos(3, 64, 14));
        stubborn.next(seen(3, 14, false, 8, 0, cow(DAISY, 6, 16)));
        var go = assertInstanceOf(Ranch.Step.Go.class, stubborn.next(seen(3, 14, false, 8, 100, cow(DAISY, 6, 16))));
        assertEquals(go, stubborn.next(seen(3, 14, false, 8, 100, cow(DAISY, 6, 16))));
        assertEquals(go, stubborn.next(seen(3, 14, false, 8, 120, cow(DAISY, 6, 16))));
    }

    @Test
    void aPairSideBySideAtTheDoorwayIsLetComeUpOneBehindTheOther() {
        // The cattle fixture: a pair abreast at the doorway, one wide, each overhanging the wall beside it, stood so for
        // thousands of ticks. At the doorway it waits for one to come up behind the other, then goes through.
        var ranch = new Ranch(new Pos(0, 64, 0), 7, 7, Ranch.Kind.COW, new Pos(3, 64, 14));
        ranch.next(seen(3, 20, false, 8, 0, cow(DAISY, 3, 23), cow(BESS, 4, 23)));
        assertEquals(new Ranch.Step.Wait(true, "Let the cows come up one behind the other"),
                ranch.next(seen(3, 14, false, 8, 10, cow(DAISY, 3, 16), cow(BESS, 4, 16))));
        var through = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 14, false, 8, 20, cow(DAISY, 3, 16), cow(BESS, 4, 17))));
        assertEquals(ranch.front(), through.target());
        // A pair that stays abreast is not waited on for ever.
        var stuck = new Ranch(new Pos(0, 64, 0), 7, 7, Ranch.Kind.COW, new Pos(3, 64, 14));
        stuck.next(seen(3, 20, false, 8, 0, cow(DAISY, 3, 23), cow(BESS, 4, 23)));
        stuck.next(seen(3, 14, false, 8, 10, cow(DAISY, 3, 16), cow(BESS, 4, 16)));
        assertInstanceOf(Ranch.Step.Go.class, stuck.next(seen(3, 14, false, 8, 100, cow(DAISY, 3, 16), cow(BESS, 4, 16))));
    }

    @Test
    void aPairJammedAtTheDoorwaysMouthIsLetWanderOffWithTheFoodPutAway() {
        // The cattle fixture: two outside at the wall's foot, in the doorway's own column, held each other there for good.
        // Five seconds so, the food is put away for ten; then the pair is led up again.
        var ranch = new Ranch(new Pos(0, 64, 0), 7, 7, Ranch.Kind.COW, new Pos(3, 64, 14));
        ranch.next(seen(3, 20, false, 8, 0, cow(DAISY, 3, 23), cow(BESS, 4, 23)));
        assertNotEquals(new Ranch.Step.Wait(false, "Let the cows wander off the way in"),
                ranch.next(seen(3, 10, false, 8, 10, cow(DAISY, 3, 13), cow(BESS, 3, 13))));
        var loosen = new Ranch.Step.Wait(false, "Let the cows wander off the way in");
        assertEquals(loosen, ranch.next(seen(3, 10, false, 8, 120, cow(DAISY, 3, 13), cow(BESS, 3, 13))));
        assertEquals(loosen, ranch.next(seen(3, 10, false, 8, 300, cow(DAISY, 2, 15), cow(BESS, 4, 16))));
        assertNotEquals(loosen, ranch.next(seen(3, 10, false, 8, 320, cow(DAISY, 2, 15), cow(BESS, 4, 16))));
        // The same at the pen's gate, one wide: a pair either side of it outside, each against the fence.
        var gated = new Ranch(new Pos(0, 64, 0), 7, 7, Ranch.Kind.COW);
        gated.next(seen(3, 12, false, 8, 0, cow(DAISY, 3, 16), cow(BESS, 4, 16)));
        gated.next(seen(3, 3, true, 8, 10, cow(DAISY, 2, 7), cow(BESS, 4, 7)));
        assertEquals(loosen, gated.next(seen(3, 3, true, 8, 120, cow(DAISY, 2, 7), cow(BESS, 4, 7))));
        // A pair that clears the mouth before five seconds are up is not waited on.
        var passing = new Ranch(new Pos(0, 64, 0), 7, 7, Ranch.Kind.COW, new Pos(3, 64, 14));
        passing.next(seen(3, 20, false, 8, 0, cow(DAISY, 3, 23), cow(BESS, 4, 23)));
        passing.next(seen(3, 10, false, 8, 10, cow(DAISY, 3, 13), cow(BESS, 2, 13)));
        passing.next(seen(3, 10, false, 8, 60, cow(DAISY, 3, 12), cow(BESS, 3, 13)));
        assertNotEquals(loosen, passing.next(seen(3, 10, false, 8, 130, cow(DAISY, 3, 11), cow(BESS, 3, 13))));
    }

    @Test
    void backOutForTheAnimalTheWaitForItToComeIntoLineBeginsAfresh() {
        var ranch = new Ranch(new Pos(0, 64, 0), 7, 7, Ranch.Kind.COW, new Pos(3, 64, 14));
        ranch.next(seen(3, 20, false, 8, 0, cow(DAISY, 3, 23)));
        assertInstanceOf(Ranch.Step.Wait.class, ranch.next(seen(3, 14, false, 8, 10, cow(DAISY, 5, 17))));
        var through = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 14, false, 8, 20, cow(DAISY, 3, 16))));
        assertEquals(ranch.front(), through.target());
        // Inside, the cow stopped at the wall's foot outside: waited for, then gone back out for.
        assertEquals(new Ranch.Step.Wait(true, "Let the cow catch up"), ranch.next(seen(3, 8, false, 8, 30, cow(DAISY, 4, 13))));
        var back = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 8, false, 8, 160, cow(DAISY, 4, 13))));
        assertTrue(back.why().startsWith("Back out through the doorway"), back.why());
        // Out at the doorway again, the cow off to one side: it waits for it to come into line again. With the first
        // wait's clock kept, it went straight back through with the cow still beside the doorway.
        assertEquals(new Ranch.Step.Wait(true, "Let the cow come up behind, in line with the doorway"),
                ranch.next(seen(3, 14, false, 8, 200, cow(DAISY, 5, 16))));
    }

    @Test
    void anAnimalThatStopsJustOutOfReachIsWaitedForAWhileThenGoneBackFor() {
        // In the open, the cow stopped five behind: waited for a while, then gone back to.
        var open = ranch();
        assertEquals(new Ranch.Step.Wait(true, "Let the cow catch up"), open.next(seen(3, 12, false, 8, 0, cow(DAISY, 3, 17))));
        var to = assertInstanceOf(Ranch.Step.Go.class, open.next(seen(3, 12, false, 8, 121, cow(DAISY, 3, 17))));
        assertEquals(new Pos(3, 64, 17), to.target());
        assertTrue(to.why().startsWith("Back to the cow"), to.why());
        // Walled in: through the doorway to the front of the gate, the cow stopped outside the wall, in line with the
        // doorway or a block beside it. Waited for, then gone back out for through the doorway, to bring it up in line
        // again, not walked to across the wall.
        var ranch = new Ranch(new Pos(0, 64, 0), 7, 7, Ranch.Kind.COW, new Pos(3, 64, 14));
        assertEquals(new Ranch.Step.Wait(true, "Let the cow catch up"), ranch.next(seen(3, 8, false, 8, 0, cow(DAISY, 4, 13))));
        assertEquals(new Ranch.Step.Wait(true, "Let the cow catch up"), ranch.next(seen(3, 8, false, 8, 100, cow(DAISY, 4, 13))));
        var back = assertInstanceOf(Ranch.Step.Go.class, ranch.next(seen(3, 8, false, 8, 121, cow(DAISY, 4, 13))));
        assertEquals(new Pos(3, 64, 14), back.target(), "out to the doorway's approach");
        assertTrue(back.why().startsWith("Back out through the doorway"), back.why());
        assertEquals(0, back.leg(), "all the way out, not in legs");
        assertTrue(back.holdFood(), "with the food in hand, to draw it on");
        // Asked again before the walk, the same walk; caught up, the wait begins afresh another time.
        assertEquals(back, ranch.next(seen(3, 8, false, 8, 121, cow(DAISY, 3, 13))));
        ranch.next(seen(3, 11, false, 8, 140, cow(DAISY, 3, 13)));
        assertEquals(new Ranch.Step.Wait(true, "Let the cow catch up"), ranch.next(seen(3, 8, false, 8, 150, cow(DAISY, 3, 13))));
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
