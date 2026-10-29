package dev.kindred.core.tactics;

import dev.kindred.core.tactics.DamageModel.Kind;
import dev.kindred.core.tactics.DamageModel.Tier;
import dev.kindred.core.tactics.DamageModel.Weapon;
import dev.kindred.core.tactics.FightOdds.Answer;
import dev.kindred.core.tactics.FightOdds.Side;
import dev.kindred.core.tactics.FightOdds.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FightOddsTest {
    private static final Weapon IRON_SWORD = new Weapon(Kind.SWORD, Tier.IRON, 0), DIAMOND_SWORD = new Weapon(Kind.SWORD, Tier.DIAMOND, 0);
    /** Full sets: iron 15 points, diamond 20 and toughness 8. */
    private static final double IRON = 15, DIAMOND = 20, DIAMOND_TOUGH = 8;

    private static Side side(Weapon weapon, double armour, double toughness) {
        return new Side(20, armour, toughness, 0, false, weapon, false, true, false, 0, 0);
    }

    @Test
    void kitAgainstKitDecidesItIronAgainstDiamondLosesTheOtherWayWinsAndEvenKitIsEven() {
        var iron = side(IRON_SWORD, IRON, 0);
        var diamond = side(DIAMOND_SWORD, DIAMOND, DIAMOND_TOUGH);
        var outclassed = FightOdds.of(iron, diamond);
        assertEquals(Verdict.LOSING, outclassed.verdict(), "Iron against diamond: " + outclassed);
        assertTrue(outclassed.best() < 0.5, "Outlasted better than two to one: " + outclassed.best());
        assertEquals(Verdict.WINNABLE, FightOdds.of(diamond, iron).verdict());
        var even = FightOdds.of(iron, iron);
        assertEquals(Verdict.EVEN, even.verdict());
        assertEquals(1.0, even.worst(), 1e-9);
        assertEquals(Verdict.LOSING, FightOdds.of(side(Weapon.HAND, 0, 0), iron).verdict(), "Bare hands against a sword");
        assertTrue(outclassed.words().startsWith("I can't win this"), outclassed.words());
        assertTrue(FightOdds.of(diamond, iron).words().startsWith("I can win this"));
    }

    @Test
    void aShieldOrHealingTipsAnEvenFightAndAnAxeCutsAShieldsShare() {
        var iron = side(IRON_SWORD, IRON, 0);
        var shielded = new Side(20, IRON, 0, 0, false, IRON_SWORD, false, true, true, 0, 0);
        assertEquals(Verdict.WINNABLE, FightOdds.of(shielded, iron).verdict(), "Its shield takes half their hits");
        var apples = new Side(20, IRON, 0, 0, false, IRON_SWORD, false, true, false, 10, 0);
        assertEquals(Verdict.WINNABLE, FightOdds.of(apples, iron).verdict(), "Golden apples to hold out on");
        var axe = side(new Weapon(Kind.AXE, Tier.IRON, 0), IRON, 0);
        double againstSword = FightOdds.perSecond(IRON_SWORD, true, IRON, 0, 0, true, 0) / FightOdds.perSecond(IRON_SWORD, true, IRON, 0, 0, false, 0);
        double againstAxe = FightOdds.perSecond(axe.weapon(), true, IRON, 0, 0, true, 0) / FightOdds.perSecond(axe.weapon(), true, IRON, 0, 0, false, 0);
        assertEquals(0.5, againstSword, 1e-9);
        assertEquals(0.8, againstAxe, 1e-9, "An axe disables the shield: it takes far fewer");
    }

    @Test
    void whatItCannotSeeIsAllowedForAtWorstAndNeverRead() {
        var iron = side(IRON_SWORD, IRON, 0);
        // Their armour glints: its Protection unread, it may be strong.
        var glinting = new Side(20, IRON, 0, 0, true, IRON_SWORD, false, true, false, 0, 0);
        var odds = FightOdds.of(iron, glinting);
        assertEquals(1.0, odds.best(), 1e-9, "At best the glint is nothing");
        assertTrue(odds.worst() < odds.best(), "At worst it costs the player's hits: " + odds);
        assertEquals(Verdict.EVEN, odds.verdict());
        var sharp = new Side(20, IRON, 0, 0, false, IRON_SWORD, true, true, false, 0, 0);
        assertTrue(FightOdds.of(iron, sharp).worst() < 1.0, "A glinting sword may be sharp");
        // An opponent not seen to land criticals may still land them.
        var calm = new Side(20, IRON, 0, 0, false, IRON_SWORD, false, false, false, 0, 0);
        var against = FightOdds.of(iron, calm);
        assertTrue(against.best() > 1.5 && against.worst() == 1.0, "Best, it does not crit; worst, it does: " + against);
        assertEquals(Verdict.EVEN, against.verdict());
        // What a diamond-armoured player did to it, it knows: its hardest hit seen counts.
        var hardHitter = new Side(20, IRON, 0, 0, false, IRON_SWORD, false, true, false, 0, 9);
        assertEquals(Verdict.LOSING, FightOdds.of(iron, hardHitter).verdict(), "A hit of nine through its armour, seen");
    }

    @Test
    void aHitOfMoreThanThreeHeartsIsLethalAndItDefendsOnlyWhereItMayEquippedAndWinning() {
        assertFalse(FightOdds.lethal(6.0), "Three hearts is not more than three");
        assertTrue(FightOdds.lethal(6.5));
        var winnable = FightOdds.of(side(DIAMOND_SWORD, DIAMOND, DIAMOND_TOUGH), side(IRON_SWORD, IRON, 0));
        var even = FightOdds.of(side(IRON_SWORD, IRON, 0), side(IRON_SWORD, IRON, 0));
        var losing = FightOdds.of(side(IRON_SWORD, IRON, 0), side(DIAMOND_SWORD, DIAMOND, DIAMOND_TOUGH));
        assertEquals(Answer.DEFEND, FightOdds.answer(true, true, winnable, false));
        assertEquals(Answer.GET_AWAY, FightOdds.answer(false, true, winnable, false), "Outside a match both joined it never strikes back (D7)");
        assertEquals(Answer.GET_AWAY, FightOdds.answer(true, false, winnable, false), "Not equipped");
        assertEquals(Answer.GET_AWAY, FightOdds.answer(true, true, even, false), "Even: away while it can be");
        assertEquals(Answer.DEFEND, FightOdds.answer(true, true, even, true), "Even and cornered: it holds");
        assertEquals(Answer.GET_AWAY, FightOdds.answer(true, true, losing, true), "Losing: away, cornered or not");
    }

    @Test
    void inADuelItJoinedAHardHitIsJudgedByTheKitNotTheHealthItTook() {
        var iron = side(IRON_SWORD, IRON, 0);
        var diamond = side(DIAMOND_SWORD, DIAMOND, DIAMOND_TOUGH);
        // Down to eight from a crit, against the same kit: by health it is losing, by kit even, and the duel goes on.
        var hurt = FightOdds.at(iron, 8);
        assertEquals(Verdict.LOSING, FightOdds.of(hurt, iron).verdict());
        assertEquals(Answer.DEFEND, FightOdds.answerInDuel(true, FightOdds.of(FightOdds.at(hurt, 20), FightOdds.at(iron, 20)), false));
        // Outclassed by kit: away, in a duel too; and with nothing to fight with.
        assertEquals(Answer.GET_AWAY, FightOdds.answerInDuel(true, FightOdds.of(iron, diamond), false));
        assertEquals(Answer.GET_AWAY, FightOdds.answerInDuel(false, FightOdds.of(iron, iron), false));
        // Outclassed with no way away (a walled arena): it fights on with what it has; with nothing, it still tries.
        assertEquals(Answer.DEFEND, FightOdds.answerInDuel(true, FightOdds.of(iron, diamond), true));
        assertEquals(Answer.GET_AWAY, FightOdds.answerInDuel(false, FightOdds.of(iron, iron), true));
        // A crit of six hearts landed by an equal: still its kit against an equal kit, and the duel goes on.
        var critted = new FightOdds.Side(20, iron.armour(), iron.toughness(), 0, false, iron.weapon(), false, iron.crits(), iron.shield(), 0, 12.8);
        assertEquals(Verdict.LOSING, FightOdds.of(FightOdds.at(iron, 20), critted).verdict(), "by the hit seen");
        assertEquals(Answer.DEFEND, FightOdds.answerInDuel(true, FightOdds.of(FightOdds.at(iron, 20), FightOdds.at(critted, 20)), false));
        assertEquals(8, FightOdds.at(iron, 8).health());
        assertEquals(iron.weapon(), FightOdds.at(iron, 8).weapon());
    }
}
