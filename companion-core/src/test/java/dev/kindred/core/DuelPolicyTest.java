package dev.kindred.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DuelPolicyTest {

    private Personality personality(int combativeness, int humor, int initiative, int caution) {
        return new Personality(initiative, caution, 50, 50, humor, combativeness, 50, 50, 50, 0);
    }

    // ----- evaluateChallenge: high combativeness -----

    @Test
    void highCombativenessIdleAccepts() {
        Personality p = personality(85, 50, 50, 50);
        // seed=5 → 5/100=0.05 < 0.85 → accept
        DuelPolicy.Decision d = DuelPolicy.evaluateChallenge(p, DuelPolicy.BusyState.IDLE, 5);
        assertTrue(d.accept());
        assertFalse(d.replyLine().isEmpty());
    }

    @Test
    void highCombativenessIdleRejectsAtHighSeed() {
        Personality p = personality(85, 50, 50, 50);
        // seed=90 → 0.90 < 0.85? No → decline
        DuelPolicy.Decision d = DuelPolicy.evaluateChallenge(p, DuelPolicy.BusyState.IDLE, 90);
        assertFalse(d.accept());
    }

    // ----- evaluateChallenge: low combativeness -----

    @Test
    void lowCombativenessDeclinesMostly() {
        Personality p = personality(20, 50, 50, 50);
        // seed=25 → 0.25 < 0.20? No → decline
        DuelPolicy.Decision d = DuelPolicy.evaluateChallenge(p, DuelPolicy.BusyState.IDLE, 25);
        assertFalse(d.accept());
    }

    @Test
    void lowCombativenessAcceptsAtLowSeed() {
        Personality p = personality(20, 50, 50, 50);
        // seed=10 → 0.10 < 0.20 → accept
        DuelPolicy.Decision d = DuelPolicy.evaluateChallenge(p, DuelPolicy.BusyState.IDLE, 10);
        assertTrue(d.accept());
    }

    // ----- Busy state multipliers -----

    @Test
    void runningSkillSuppressesAcceptance() {
        Personality p = personality(85, 50, 50, 50);
        // prob = 0.85 * 0.3 = 0.255; seed=50 → decline
        DuelPolicy.Decision d = DuelPolicy.evaluateChallenge(p, DuelPolicy.BusyState.RUNNING_SKILL, 50);
        assertFalse(d.accept());
        // Mentions busy in reply
        assertTrue(d.replyLine().toLowerCase().contains("busy") || d.replyLine().toLowerCase().contains("working")
                || d.replyLine().toLowerCase().contains("later"));
    }

    @Test
    void runningSkillCanStillAcceptAtLowSeed() {
        Personality p = personality(85, 50, 50, 50);
        // prob = 0.255; seed=20 → 0.20 < 0.255 → accept
        DuelPolicy.Decision d = DuelPolicy.evaluateChallenge(p, DuelPolicy.BusyState.RUNNING_SKILL, 20);
        assertTrue(d.accept());
    }

    @Test
    void followingPartialSuppression() {
        Personality p = personality(85, 50, 50, 50);
        // prob = 0.85 * 0.7 = 0.595; seed=70 → 0.70 > 0.595 → decline
        DuelPolicy.Decision d = DuelPolicy.evaluateChallenge(p, DuelPolicy.BusyState.FOLLOWING, 70);
        assertFalse(d.accept());
        // seed=50 → accept
        d = DuelPolicy.evaluateChallenge(p, DuelPolicy.BusyState.FOLLOWING, 50);
        assertTrue(d.accept());
    }

    // ----- Initiate -----

    @Test
    void belowThresholdNeverInitiates() {
        // combativeness < 60 → never
        Personality p = personality(50, 50, 50, 50);
        for (int seed = 0; seed < 100; seed++) {
            assertFalse(DuelPolicy.evaluateInitiate(p, DuelPolicy.BusyState.IDLE, seed).accept(),
                    "should not initiate with combativeness=50 at seed=" + seed);
        }
    }

    @Test
    void highCombativenessInitiatesAtLowSeed() {
        // combativeness=100, initiative=100 → high prob
        Personality p = personality(100, 50, 100, 0);
        // prob ≈ 0.04 + 0.15 + 0.10 = 0.29; seed=25 → accept
        DuelPolicy.Decision d = DuelPolicy.evaluateInitiate(p, DuelPolicy.BusyState.IDLE, 25);
        assertTrue(d.accept());
        assertFalse(d.replyLine().isEmpty());
    }

    @Test
    void skillBlocksInitiate() {
        Personality p = personality(100, 50, 100, 0);
        DuelPolicy.Decision d = DuelPolicy.evaluateInitiate(p, DuelPolicy.BusyState.RUNNING_SKILL, 0);
        assertFalse(d.accept());
    }

    // ----- Hit-back lines -----

    @Test
    void hitBackHighCombativenessIsAggressive() {
        Personality p = personality(90, 50, 50, 50);
        String line = DuelPolicy.hitBackLine(p);
        assertFalse(line.isEmpty());
        // Should not be the apologetic wording
        assertFalse(line.toLowerCase().contains("please don't"));
    }

    @Test
    void hitBackLowCombativenessIsGentle() {
        Personality p = personality(10, 50, 50, 50);
        String line = DuelPolicy.hitBackLine(p);
        // Low combativeness → more gentle
        assertFalse(line.isEmpty());
    }

    @Test
    void hitBackHumorVariant() {
        Personality p = personality(90, 90, 50, 50);
        String line = DuelPolicy.hitBackLine(p);
        // High humor → funny tone
        assertFalse(line.isEmpty());
    }

    // ----- Post-duel lines -----

    @Test
    void postDuelWinAndLossNotEmpty() {
        Personality p = personality(75, 50, 50, 50);
        assertFalse(DuelPolicy.postDuelLine(true, p).isEmpty());
        assertFalse(DuelPolicy.postDuelLine(false, p).isEmpty());
    }

    // ----- Null guards -----

    @Test
    void nullPersonalityThrows() {
        assertThrows(NullPointerException.class, () -> DuelPolicy.evaluateChallenge(null, DuelPolicy.BusyState.IDLE, 0));
        assertThrows(NullPointerException.class, () -> DuelPolicy.evaluateInitiate(null, DuelPolicy.BusyState.IDLE, 0));
        assertThrows(NullPointerException.class, () -> DuelPolicy.hitBackLine(null));
        assertThrows(NullPointerException.class, () -> DuelPolicy.postDuelLine(true, null));
    }

    @Test
    void nullBusyStateThrows() {
        Personality p = personality(50, 50, 50, 50);
        assertThrows(NullPointerException.class, () -> DuelPolicy.evaluateChallenge(p, null, 0));
        assertThrows(NullPointerException.class, () -> DuelPolicy.evaluateInitiate(p, null, 0));
    }
}
