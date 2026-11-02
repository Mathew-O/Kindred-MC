package dev.kindred.core.tactics;

import dev.kindred.core.tactics.CombatProposals.Facts;
import dev.kindred.core.tactics.CombatProposals.Tactic;
import dev.kindred.core.tactics.Melee.Move;
import dev.kindred.core.tactics.Melee.Sight;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CombatProposalsTest {
    private static Facts facts(double health, boolean shield, FightOdds.Verdict odds) {
        return new Facts(health, 20, shield, true, odds, 4);
    }

    @Test
    void onlyTacticsThatMakeSenseForTheFightAreOffered() {
        assertEquals(List.of(Tactic.PRESS, Tactic.COUNTER, Tactic.HIT_AND_RUN, Tactic.DISENGAGE), CombatProposals.offered(facts(20, true, FightOdds.Verdict.EVEN)));
        // No shield, no counter; losing, no press; low, only getting away is left.
        assertEquals(List.of(Tactic.PRESS, Tactic.HIT_AND_RUN, Tactic.DISENGAGE), CombatProposals.offered(facts(20, false, FightOdds.Verdict.WINNABLE)));
        assertEquals(List.of(Tactic.COUNTER, Tactic.HIT_AND_RUN, Tactic.DISENGAGE), CombatProposals.offered(facts(14, true, FightOdds.Verdict.LOSING)));
        assertEquals(List.of(Tactic.DISENGAGE), CombatProposals.offered(facts(5, false, FightOdds.Verdict.LOSING)));
        assertTrue(CombatProposals.question(facts(20, true, FightOdds.Verdict.EVEN)).contains("Offered: press, counter, hit_and_run, disengage."));
        assertEquals(Optional.of(Tactic.HIT_AND_RUN), CombatProposals.parse(" Hit_and_run "));
        assertTrue(CombatProposals.parse("swing wildly").isEmpty(), "nothing but an offered tactic is read");
    }

    @Test
    void aProposalLeansTheMovesBoundedAndOpensNoMoveButAGetaway() {
        var sight = new Sight(2.5, 0.4, 20, false, false, true, false, 20, true);
        var plain = Melee.utilities(sight);
        for (Tactic t : Tactic.values()) {
            var leaned = CombatProposals.lean(plain, t);
            for (var move : leaned.keySet()) {
                if (move == Move.RETREAT && t == Tactic.DISENGAGE) continue;
                assertTrue(plain.containsKey(move), t + " opened " + move);
                assertTrue(Math.abs(leaned.get(move) - plain.get(move)) <= CombatProposals.MAX_LEAN + 1e-9);
            }
        }
        // Not losing and unhurt, the melee would step back from a swing about to come; pressed, it strafes on instead.
        assertEquals(Move.BACK_OFF, new Melee().next(sight, 0).move());
        var pressed = new Melee();
        pressed.lean(Tactic.PRESS);
        assertNotEquals(Move.BACK_OFF, pressed.next(sight, 0).move());
        // Told to disengage, it gets away though not low.
        var leaving = new Melee();
        leaving.lean(Tactic.DISENGAGE);
        assertEquals(Move.RETREAT, leaving.next(sight, 0).move());
        // The lean lifted, the melee's own choice again.
        leaving.lean(null);
        assertEquals(Move.BACK_OFF, leaving.next(sight, 100).move());
    }

    @Test
    void oneQuestionAtATimeAndAnAnswerCheckedAgainWhenItComesBack() {
        var gate = new CombatProposals.Gate();
        assertTrue(gate.mayAsk(0));
        gate.asked(0);
        assertFalse(gate.mayAsk(500), "one out at a time");
        // Back after the fight changed: pressing is no longer valid at five health, so it is not adopted.
        assertTrue(gate.answered(Optional.of(Tactic.PRESS), "it is hurt", facts(5, true, FightOdds.Verdict.LOSING), 40).isEmpty());
        assertTrue(gate.dropped().contains("no longer valid"));
        assertFalse(gate.mayAsk(100), "cooled down between questions");
        assertTrue(gate.mayAsk(CombatProposals.COOLDOWN_TICKS));
        gate.asked(CombatProposals.COOLDOWN_TICKS);
        var adopted = gate.answered(Optional.of(Tactic.COUNTER), "it swings early", facts(16, true, FightOdds.Verdict.EVEN), 220);
        assertEquals(Tactic.COUNTER, adopted.orElseThrow().tactic());
        assertTrue(gate.current(facts(16, true, FightOdds.Verdict.EVEN), 300).isPresent());
        // Its shield broken: dropped at once.
        assertTrue(gate.current(facts(16, false, FightOdds.Verdict.EVEN), 301).isEmpty());
        assertTrue(gate.dropped().contains("counter is no longer valid"));
        // No answer at all: nothing adopted, and the fight goes on as the melee has it.
        gate.asked(1000);
        assertTrue(gate.answered(Optional.empty(), "", facts(20, true, FightOdds.Verdict.EVEN), 1010).isEmpty());
        // An adopted one runs its time.
        gate.asked(2000);
        gate.answered(Optional.of(Tactic.HIT_AND_RUN), "", facts(20, true, FightOdds.Verdict.EVEN), 2000);
        assertTrue(gate.current(facts(20, true, FightOdds.Verdict.EVEN), 2000 + CombatProposals.HOLD_TICKS).isEmpty());
        assertTrue(gate.dropped().contains("ran its time"));
    }
}
