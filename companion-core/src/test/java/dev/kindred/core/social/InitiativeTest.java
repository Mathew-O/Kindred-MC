package dev.kindred.core.social;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how often an AI player may speak first to a person: once in three minutes by default, as their
 * own preference stretches or shortens it; never to someone fighting, building fast, waiting for a match or away, nor out
 * of earshot, except a warning of danger, which has its own thirty seconds; an offer or a request once an hour; and someone
 * who answers earns more lines than someone who never does, both drifting back over a day.
 */
class InitiativeTest {
    private static final UUID JO = UUID.randomUUID(), KIT = UUID.randomUUID();
    private static final Initiative.State FREE = Initiative.State.FREE;

    @Test
    void oneUnaskedLineInThreeMinutesAsThePersonAsked() {
        var budget = new Initiative();
        assertTrue(budget.may(JO, FREE, Initiative.Occasion.BANTER, "", null, 0));
        budget.said(JO, Initiative.Occasion.BANTER, "", null, 0);
        assertFalse(budget.may(JO, FREE, Initiative.Occasion.BANTER, "", null, Initiative.BASE_MILLIS - 1));
        assertTrue(budget.may(JO, FREE, Initiative.Occasion.BANTER, "", null, Initiative.BASE_MILLIS));
        assertTrue(budget.may(KIT, FREE, Initiative.Occasion.BANTER, "", null, 1), "Each person their own budget");
        var fewer = ListenerPreferences.defaults(JO).withUnasked(ListenerPreferences.Unasked.FEWER);
        assertFalse(budget.may(JO, FREE, Initiative.Occasion.BANTER, "", fewer, Initiative.BASE_MILLIS), "Fewer: twice as long");
        assertTrue(budget.may(JO, FREE, Initiative.Occasion.BANTER, "", fewer, 2 * Initiative.BASE_MILLIS));
        var more = ListenerPreferences.defaults(JO).withUnasked(ListenerPreferences.Unasked.MORE);
        assertTrue(budget.may(JO, FREE, Initiative.Occasion.BANTER, "", more, Initiative.BASE_MILLIS / 2), "More: half as long");
    }

    @Test
    void noneToSomeoneBusyOrAwayButAWarningForSomeoneFighting() {
        var budget = new Initiative();
        var fighting = new Initiative.State(true, true, false, false, false);
        var building = new Initiative.State(true, false, true, false, false);
        var countdown = new Initiative.State(true, false, false, true, false);
        var away = new Initiative.State(true, false, false, false, true);
        var far = new Initiative.State(false, false, false, false, false);
        for (var state : new Initiative.State[] {fighting, building, countdown, away, far}) {
            assertFalse(budget.may(JO, state, Initiative.Occasion.BANTER, "", null, 0), state.busy());
            assertFalse(budget.may(JO, state, Initiative.Occasion.OFFER, "jo", null, 0), state.busy());
        }
        assertEquals("fighting", fighting.busy());
        assertNull(FREE.busy());
        assertTrue(budget.may(JO, fighting, Initiative.Occasion.WARNING, "jo", null, 0), "A warning, though they are fighting");
        assertFalse(budget.may(JO, far, Initiative.Occasion.WARNING, "jo", null, 0), "Not beyond earshot");
        assertFalse(budget.may(JO, countdown, Initiative.Occasion.WARNING, "jo", null, 0));
        budget.said(JO, Initiative.Occasion.BANTER, "", null, 0);
        assertTrue(budget.may(JO, FREE, Initiative.Occasion.WARNING, "jo", null, 1), "A warning is not held back by the budget");
        budget.said(JO, Initiative.Occasion.WARNING, "jo", null, 1);
        assertFalse(budget.may(JO, FREE, Initiative.Occasion.WARNING, "jo", null, 1 + Initiative.WARNING_MILLIS - 1));
        assertTrue(budget.may(JO, FREE, Initiative.Occasion.WARNING, "jo", null, 1 + Initiative.WARNING_MILLIS));
    }

    @Test
    void anOfferOrARequestOnceAnHour() {
        var budget = new Initiative();
        budget.said(JO, Initiative.Occasion.REQUEST, "flint", null, 0);
        long later = Initiative.BASE_MILLIS * 2;
        assertFalse(budget.may(JO, FREE, Initiative.Occasion.REQUEST, "flint", null, later), "The same need not again within the hour");
        assertTrue(budget.may(JO, FREE, Initiative.Occasion.REQUEST, "string", null, later), "Another need may be asked");
        assertTrue(budget.may(JO, FREE, Initiative.Occasion.REQUEST, "flint", null, Initiative.HOUR));
        budget.said(JO, Initiative.Occasion.OFFER, JO.toString(), null, 0);
        assertFalse(budget.may(JO, FREE, Initiative.Occasion.OFFER, JO.toString(), null, later));
        assertTrue(budget.may(JO, FREE, Initiative.Occasion.OFFER, JO.toString(), null, Initiative.HOUR + Initiative.BASE_MILLIS));
    }

    @Test
    void whoAnswersEarnsMoreAndWhoNeverDoesGetsLessBothDriftingBack() {
        var budget = new Initiative();
        long now = 0;
        int toJo = 0, toKit = 0;
        // Ten minutes, a look every ten seconds: Jo answers each line, Kit never does.
        for (; now <= 600_000; now += 10_000) {
            if (budget.may(JO, FREE, Initiative.Occasion.BANTER, "", null, now)) {
                budget.said(JO, Initiative.Occasion.BANTER, "", null, now);
                budget.answered(JO, now + 5_000);
                toJo++;
            }
            if (budget.may(KIT, FREE, Initiative.Occasion.BANTER, "", null, now)) {
                budget.said(KIT, Initiative.Occasion.BANTER, "", null, now);
                toKit++;
            }
        }
        assertTrue(toJo > toKit, "Jo " + toJo + " lines, Kit " + toKit);
        assertTrue(budget.earned(JO, now) > 0 && budget.earned(KIT, now) < 0);
        assertTrue(budget.interval(KIT, null, now) > Initiative.BASE_MILLIS && budget.interval(JO, null, now) < Initiative.BASE_MILLIS);
        assertEquals(0, budget.earned(KIT, now + Initiative.DAY), 1e-9, "Back to nothing in a day");
        // A reply long after the line does not count.
        var late = new Initiative();
        late.said(JO, Initiative.Occasion.BANTER, "", null, 0);
        late.answered(JO, Initiative.ANSWER_WINDOW + 1);
        assertEquals(0, late.earned(JO, Initiative.ANSWER_WINDOW + 1), 1e-9);
    }
}
