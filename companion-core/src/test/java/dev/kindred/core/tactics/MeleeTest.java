package dev.kindred.core.tactics;

import dev.kindred.core.tactics.Melee.Move;
import dev.kindred.core.tactics.Melee.Sight;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MeleeTest {
    private static Sight sight(double distance, double charge, double health, boolean food, boolean shield, boolean opponentReady) {
        return new Sight(distance, charge, health, food, shield, opponentReady, false, 20, true);
    }

    @Test
    void itClosesInStrikesWhenItsSwingIsFullAndBetweenSwingsKeepsMoving() {
        assertEquals(Move.APPROACH, new Melee().next(sight(8, 1, 20, false, false, false), 0).move());
        assertEquals(Move.STRIKE, new Melee().next(sight(2.5, 1, 20, false, false, true), 0).move());
        // Its swing wound up past half, in reach: the jump for the critical begins, so the blow lands full.
        assertEquals(Move.STRIKE, new Melee().next(sight(2.5, 0.7, 20, false, false, false), 0).move());
        assertEquals("jump for a critical as the swing recovers", new Melee().next(sight(2.5, 0.7, 20, false, false, false), 0).why());
        // Between swings, its opponent not yet able to swing again: strafe round it.
        assertEquals(Move.STRAFE, new Melee().next(sight(2.5, 0.4, 20, false, false, false), 0).move());
        // Just out of reach with the swing still recovering: it waits there rather than walking in.
        assertEquals(Move.STRAFE, new Melee().next(sight(3.5, 0.3, 20, false, false, false), 0).move());
    }

    @Test
    void itAnswersASwingAboutToComeWithItsShieldOrBySteppingOutOfReach() {
        assertEquals(Move.BLOCK, new Melee().next(sight(2.5, 0.4, 20, false, true, true), 0).move(), "the shield up");
        assertEquals(Move.BACK_OFF, new Melee().next(sight(2.5, 0.4, 20, false, false, true), 0).move(), "no shield: out of its reach");
        // Its swing full and the opponent's about to come: the opponent's swing on the shield first, and with no shield
        // the strike at once; at a raised shield only if nothing better is open.
        assertEquals(Move.BLOCK, new Melee().next(sight(2.5, 1, 20, false, true, true), 0).move());
        assertEquals(Move.STRIKE, new Melee().next(sight(2.5, 1, 20, false, false, true), 0).move());
        // The opponent's swing taken (no longer ready): the strike while it recovers.
        var melee = new Melee();
        melee.next(sight(2.5, 1, 20, false, true, true), 0);
        assertEquals(Move.STRIKE, melee.next(sight(2.5, 1, 20, false, true, false), 3).move());
        // One ready behind its own shield is as much a threat: the shield up against it too.
        assertEquals(Move.BLOCK, new Melee().next(new Sight(2.5, 0.4, 20, false, true, true, true, 20, true), 0).move());
        // A swing that never comes is waited for only so long.
        var patient = new Melee();
        assertEquals(Move.BLOCK, patient.next(sight(2.5, 1, 20, false, true, true), 0).move());
        assertEquals(Move.BLOCK, patient.next(sight(2.5, 1, 20, false, true, true), Melee.PATIENCE - 1).move());
        assertEquals(Move.STRIKE, patient.next(sight(2.5, 1, 20, false, true, true), Melee.PATIENCE).move());
        // Nor, just out of reach, does it stand behind its shield for ever: it closes in.
        var waiting = new Melee();
        assertEquals(Move.BLOCK, waiting.next(sight(3.5, 1, 20, false, true, true), 0).move());
        assertEquals(Move.APPROACH, waiting.next(sight(3.5, 1, 20, false, true, true), Melee.PATIENCE).move());
        assertEquals(Move.APPROACH, waiting.next(sight(3.4, 1, 20, false, true, true), Melee.PATIENCE + 1).move());
        var atShield = new Sight(2.5, 1, 20, false, false, false, true, 20, true);
        assertEquals(Move.STRIKE, new Melee().next(atShield, 0).move());
        assertTrue(Melee.utilities(atShield).get(Move.STRIKE) < Melee.utilities(sight(2.5, 1, 20, false, false, false)).get(Move.STRIKE));
    }

    @Test
    void losingItGetsAwayAndWellOutOfReachHurtItEats() {
        var losing = new Sight(2.5, 1, 5, true, false, true, false, 14, true);
        assertEquals(Move.RETREAT, new Melee().next(losing, 0).move());
        // Low but winning: it fights on.
        assertEquals(Move.STRIKE, new Melee().next(new Sight(2.5, 1, 5, true, false, true, false, 3, true), 0).move());
        assertEquals(Move.EAT, new Melee().next(sight(9, 1, 10, true, false, false), 0).move());
        assertEquals(Move.APPROACH, new Melee().next(sight(9, 1, 10, false, false, false), 0).move(), "no food: back to it");
        assertEquals(Move.APPROACH, new Melee().next(sight(9, 1, 18, true, false, false), 0).move(), "not hurt enough to stop for it");
    }

    @Test
    void whatItHasLearnedOfItsOpponentShiftsTheMovesItsControlWouldMake() {
        // Its swing full, against one known to raise its shield as it is: the strike scores higher, before the shield is up.
        var blocker = new Sight(2.5, 1, 20, false, false, false, false, 20, true, 0.8, 0.5);
        assertTrue(Melee.utilities(blocker).get(Move.STRIKE) > Melee.utilities(sight(2.5, 1, 20, false, false, false)).get(Move.STRIKE));
        // Against one that swings uncharged, the shield is not raised for every swing: it strafes instead.
        var spammer = new Sight(2.5, 0.4, 20, false, true, true, false, 20, true, 0.5, 0.8);
        assertEquals(Move.STRAFE, new Melee().next(spammer, 0).move());
        // The control: with nothing learned, the shield as before.
        assertEquals(Move.BLOCK, new Melee().next(sight(2.5, 0.4, 20, false, true, true), 0).move());
    }

    @Test
    void aMoveIsKeptAWhileUnlessAnotherIsMuchBetterAndAStrikeIsTakenAtOnce() {
        var melee = new Melee();
        assertEquals(Move.STRAFE, melee.next(sight(2.5, 0.4, 20, false, false, false), 0).move());
        // Its opponent now about to swing: stepping back scores better, but within the commitment the strafe goes on...
        assertEquals(Move.STRAFE, melee.next(sight(2.5, 0.4, 20, false, false, true), 2).move());
        // ...and after it, the better move is taken.
        assertEquals(Move.BACK_OFF, melee.next(sight(2.5, 0.4, 20, false, false, true), 6).move());
        // The swing full: a strike at once, whatever it was doing.
        assertEquals(Move.STRIKE, melee.next(sight(2.5, 1, 20, false, false, true), 7).move());
        // The same sight, the same move.
        assertEquals(new Melee().next(sight(2.5, 0.4, 20, false, true, true), 0), new Melee().next(sight(2.5, 0.4, 20, false, true, true), 0));
    }
}
