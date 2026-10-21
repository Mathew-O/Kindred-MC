package dev.kindred.core.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobSensesTest {
    @Test
    void rangesAreVanillasOwn() {
        assertEquals(16, MobSenses.of("wither_skeleton", true).range());
        assertEquals(35, MobSenses.of("zombie", true).range());
        assertEquals(48, MobSenses.of("blaze", true).range());
        assertEquals(100, MobSenses.of("ghast", true).range());
        assertEquals(16, MobSenses.of("some_modded_monster", true).range(), "an unlisted hostile mob has vanilla's default");
        assertEquals(MobSenses.Temper.PEACEFUL, MobSenses.of("cow", false).temper());
    }

    @Test
    void aWitherSkeletonNoticesAtSixteenAndASneakingPlayerAtFourFifthsOfThat() {
        var skeleton = MobSenses.of("wither_skeleton", true);
        assertTrue(MobSenses.wouldAttack(skeleton, 15.9, true, false));
        assertFalse(MobSenses.wouldAttack(skeleton, 16.1, true, false));
        assertFalse(MobSenses.wouldAttack(skeleton, 13.5, true, true), "sneaking: noticed only within 12.8");
        assertTrue(MobSenses.wouldAttack(skeleton, 12.5, true, true));
        assertFalse(MobSenses.wouldAttack(skeleton, 5, false, false), "no line of sight, no notice");
        assertTrue(skeleton.outpaces(), "a wither skeleton outpaces a walking player");
    }

    @Test
    void provokedMobsNeverStartAFightByNoticing() {
        assertFalse(MobSenses.wouldAttack(MobSenses.of("enderman", true), 3, true, false));
        assertFalse(MobSenses.wouldAttack(MobSenses.of("zombified_piglin", true), 3, true, false));
        assertEquals(0, MobSenses.unease(MobSenses.of("enderman", true), 3, true));
    }

    @Test
    void uneaseRisesTowardTheMobAndStopsAtItsRange() {
        var skeleton = MobSenses.of("skeleton", true);
        assertEquals(0, MobSenses.unease(skeleton, 18.6, true), "beyond its range and two and a half blocks");
        assertTrue(MobSenses.unease(skeleton, 15.5, true) >= 0.5, "just inside its range is firmly minded");
        assertTrue(MobSenses.unease(skeleton, 4, true) > MobSenses.unease(skeleton, 10, true));
        assertEquals(1.0, MobSenses.unease(skeleton, 0, true), 1e-9);
        assertEquals(0, MobSenses.unease(skeleton, 8, false), "out of sight it counts for a third of its range only");
        assertTrue(MobSenses.unease(skeleton, 2, false) > 0);
    }
}
