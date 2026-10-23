package dev.kindred.core.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CombatRulesetTest {
    /** A sword swings 1.6 times a second at full charge, an iron axe 0.9 times: twelve and a half and 22.2 ticks. */
    @Test
    void aSwingChargesOverTwentyTicksOverTheAttackSpeed() {
        assertEquals(0.5f / 12.5f, CombatRuleset.charge(0, 1.6), 1e-6, "half a tick on as the attack lands");
        assertEquals(1f, CombatRuleset.charge(12, 1.6), 1e-6);
        assertEquals(1f, CombatRuleset.charge(40, 1.6), 1e-6, "never past full");
        assertEquals(11, CombatRuleset.ticksToStrong(1.6), "a sword's next strong hit");
        assertEquals(20, CombatRuleset.ticksToStrong(0.9), "an iron axe's");
        assertEquals(5, CombatRuleset.ticksToStrong(4.0), "a bare hand's");
        assertFalse(CombatRuleset.strong(0.9f), "above 0.9, not at it");
        assertTrue(CombatRuleset.strong(0.91f));
        assertThrows(IllegalArgumentException.class, () -> CombatRuleset.charge(1, 0));
    }

    @Test
    void aCriticalHitIsStrongFallingAndInTheAirWithNothingElseInTheWay() {
        var falling = new CombatRuleset.Swing(1f, 0.3, false, false, false, false, false, false);
        assertTrue(CombatRuleset.critical(falling));
        assertFalse(CombatRuleset.critical(new CombatRuleset.Swing(0.85f, 0.3, false, false, false, false, false, false)), "not strong");
        assertFalse(CombatRuleset.critical(new CombatRuleset.Swing(1f, 0, false, false, false, false, false, false)), "rising, not falling");
        assertFalse(CombatRuleset.critical(new CombatRuleset.Swing(1f, 0.3, true, false, false, false, false, false)), "on the ground");
        assertFalse(CombatRuleset.critical(new CombatRuleset.Swing(1f, 0.3, false, true, false, false, false, false)), "climbing");
        assertFalse(CombatRuleset.critical(new CombatRuleset.Swing(1f, 0.3, false, false, true, false, false, false)), "in water");
        assertFalse(CombatRuleset.critical(new CombatRuleset.Swing(1f, 0.3, false, false, false, true, false, false)), "blind");
        assertFalse(CombatRuleset.critical(new CombatRuleset.Swing(1f, 0.3, false, false, false, false, true, false)), "riding");
        assertFalse(CombatRuleset.critical(new CombatRuleset.Swing(1f, 0.3, false, false, false, false, false, true)), "sprinting");
        // The damage model's critical multiplier is the ruleset's.
        assertEquals(DamageModel.hit(DamageModel.Weapon.HAND, 1, false, 0, 0) * CombatRuleset.CRITICAL,
                DamageModel.hit(DamageModel.Weapon.HAND, 1, true, 0, 0), 1e-9);
    }

    @Test
    void aSprintingStrongHitKnocksBackAndOnlyAStillSwordHitOnTheGroundSweeps() {
        var sprinting = new CombatRuleset.Swing(1f, 0, true, false, false, false, false, true);
        assertTrue(CombatRuleset.sprintKnockback(sprinting));
        assertFalse(CombatRuleset.sprintKnockback(new CombatRuleset.Swing(0.5f, 0, true, false, false, false, false, true)), "a weak hit");
        assertTrue(CombatRuleset.sweeps(CombatRuleset.Swing.standing(1f), true, true));
        assertFalse(CombatRuleset.sweeps(CombatRuleset.Swing.standing(1f), false, true), "not a sword");
        assertFalse(CombatRuleset.sweeps(CombatRuleset.Swing.standing(1f), true, false), "moving fast");
        assertFalse(CombatRuleset.sweeps(sprinting, true, true), "a sprint's knockback instead");
        assertFalse(CombatRuleset.sweeps(new CombatRuleset.Swing(1f, 0.3, false, false, false, false, false, false), true, true), "a critical instead");
    }

    /** Twenty ticks of immunity after a hit; in the first ten another hit lands only by what it exceeds the last by. */
    @Test
    void aSecondHitWithinTenTicksLandsOnlyByWhatItExceedsTheFirst() {
        assertEquals(0, CombatRuleset.landed(6, 7, 3), "no larger, nothing");
        assertEquals(2, CombatRuleset.landed(9, 7, 9), 1e-9, "only the excess");
        assertEquals(6, CombatRuleset.landed(6, 7, 10), 1e-9, "the first half is over");
        assertEquals(20, CombatRuleset.REGEN_TICKS);
        assertEquals(10, CombatRuleset.IMMUNE_TICKS);
    }

    @Test
    void aShieldBlocksFromTheFrontOnceHeldUpFiveTicks() {
        assertFalse(CombatRuleset.blocks(4, 1), "not up long enough");
        assertTrue(CombatRuleset.blocks(5, 1));
        assertTrue(CombatRuleset.blocks(5, 0.05), "anywhere in front");
        assertFalse(CombatRuleset.blocks(30, -0.05), "from behind");
        assertEquals(100, CombatRuleset.SHIELD_DISABLED_TICKS);
        assertEquals(3.0, CombatRuleset.ENTITY_REACH);
        assertEquals(4.5, CombatRuleset.BLOCK_REACH);
    }
}
