package dev.kindred.core.outing;

import dev.kindred.core.Personality;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: an outing goes only with someone's yes, within its minute; who was really there when it arrived is who
 * came; it never runs past ten game minutes; and an AI player says yes only with nothing of its own on and company to its
 * liking.
 *
 * Technical: E-27a, pure: {@link Outing}'s states and bounds, {@link OutingChoice}'s rule.
 */
final class OutingTest {
    private static final UUID HOST = UUID.randomUUID(), STEVE = UUID.randomUUID(), ALEX = UUID.randomUUID();
    private static final Outing.Destination LAKE = new Outing.Destination("a lake", "minecraft:overworld", 20, 64, 3);

    @Test
    void onlyAYesWithinItsMinuteSetsItOffAndThoseThereAreWhoCame() {
        var nobody = new Outing(HOST, "Echo", LAKE, 0);
        assertFalse(nobody.setOff(Outing.INVITE_TICKS - 1), "Still waiting for a yes");
        assertTrue(nobody.setOff(Outing.INVITE_TICKS));
        assertEquals(Outing.State.CANCELLED, nobody.state());
        assertEquals("nobody wanted to come", nobody.why());

        var late = new Outing(HOST, "Echo", LAKE, 0);
        assertFalse(late.accept(STEVE, Outing.INVITE_TICKS + 1), "A yes after its minute is not taken");
        assertFalse(late.accept(HOST, 10), "The host does not invite itself");

        var outing = new Outing(HOST, "Echo", LAKE, 0);
        assertTrue(outing.accept(STEVE, 100));
        assertTrue(outing.accept(ALEX, 120));
        assertFalse(outing.accept(STEVE, 130), "Once each");
        assertTrue(outing.setOffNow(140));
        assertEquals(Outing.State.GOING, outing.state());
        assertFalse(outing.accept(UUID.randomUUID(), 150), "Not once it is off");

        outing.arrived(Set.of(STEVE, UUID.randomUUID()), 400); // Alex stayed behind; a passer-by never said yes.
        assertEquals(Set.of(STEVE), outing.there());
        assertFalse(outing.timeToGoBack(400 + Outing.STAY_TICKS - 1));
        assertTrue(outing.timeToGoBack(400 + Outing.STAY_TICKS));
        outing.goingBack(1_700);
        outing.back(2_000);
        assertEquals(Outing.State.DONE, outing.state());
        assertFalse(outing.cancel("late", 2_100), "Over is over");
    }

    @Test
    void itNeverRunsPastTenGameMinutes() {
        var outing = new Outing(HOST, "Echo", LAKE, 0);
        outing.accept(STEVE, 10);
        outing.setOffNow(20);
        assertFalse(outing.overdue(Outing.MOST_TICKS));
        assertTrue(outing.overdue(Outing.MOST_TICKS + 1));
        assertTrue(outing.cancel("too long", Outing.MOST_TICKS + 1));
        assertTrue(outing.over());
        assertFalse(outing.overdue(Outing.MOST_TICKS + 2), "Not overdue once ended");
    }

    @Test
    void anAiPlayerSaysYesOnlyWithNothingOfItsOwnOnAndCompanyToItsLiking() {
        var sociable = new Personality(50, 50, 50, 70, 50, 50, 50, 50, 50, 0); // Sociability 70.
        var loner = new Personality(50, 50, 50, 20, 50, 50, 50, 50, 50, 0); // Sociability 20.
        assertTrue(OutingChoice.answer(false, false, false, false, sociable).yes());
        assertEquals("has its own goal", OutingChoice.answer(true, false, false, false, sociable).why());
        assertEquals("busy", OutingChoice.answer(false, true, false, false, sociable).why());
        assertEquals("in danger", OutingChoice.answer(false, false, true, false, sociable).why());
        assertEquals("in a match", OutingChoice.answer(false, false, false, true, sociable).why());
        assertEquals("not one for company", OutingChoice.answer(false, false, false, false, loner).why());
    }
}
