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
        // Between swings, its opponent not yet able to swing again: strafe round it.
        assertEquals(Move.STRAFE, new Melee().next(sight(2.5, 0.4, 20, false, false, false), 0).move());
        // Just out of reach with the swing still recovering: it waits there rather than walking in.
        assertEquals(Move.STRAFE, new Melee().next(sight(3.5, 0.3, 20, false, false, false), 0).move());
    }

    @Test
    void itAnswersASwingAboutToComeWithItsShieldOrBySteppingOutOfReach() {
        assertEquals(Move.BLOCK, new Melee().next(sight(2.5, 0.4, 20, false, true, true), 0).move(), "the shield up");
        assertEquals(Move.BACK_OFF, new Melee().next(sight(2.5, 0.4, 20, false, false, true), 0).move(), "no shield: out of its reach");
        // Its swing full, it strikes, about to be hit or not; at a raised shield only if nothing better is open.
        assertEquals(Move.STRIKE, new Melee().next(sight(2.5, 1, 20, false, true, true), 0).move());
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
