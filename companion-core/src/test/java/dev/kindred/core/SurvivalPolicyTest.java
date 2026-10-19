package dev.kindred.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SurvivalPolicyTest {

    private final Personality balanced = Personality.defaults();
    private final Personality timid = Personality.defaults()
            .withTrait(Personality.Trait.COMBATIVENESS, 10)
            .withTrait(Personality.Trait.CAUTION, 80);
    private final Personality fighter = Personality.defaults()
            .withTrait(Personality.Trait.COMBATIVENESS, 90)
            .withTrait(Personality.Trait.INITIATIVE, 85);

    @Test
    void shouldEatOnlyWhenInjuredAndCooldownElapsed() {
        // Full health -> should not eat
        assertFalse(SurvivalPolicy.shouldEat(20.0f, 20.0f, 5, 0L, 5000L));

        // Injured with nutrition -> should eat if cooldown passed
        assertTrue(SurvivalPolicy.shouldEat(14.0f, 20.0f, 4, 0L, 4000L));

        // Injured but cooldown not elapsed -> should not eat
        assertFalse(SurvivalPolicy.shouldEat(14.0f, 20.0f, 4, 2000L, 3000L));

        // Zero nutrition -> should not eat
        assertFalse(SurvivalPolicy.shouldEat(10.0f, 20.0f, 0, 0L, 5000L));
    }

    @Test
    void identifiesHazardousBlocks() {
        assertTrue(SurvivalPolicy.isHazardousBlock("minecraft:magma_block"));
        assertTrue(SurvivalPolicy.isHazardousBlock("minecraft:fire"));
        assertTrue(SurvivalPolicy.isHazardousBlock("minecraft:lava"));
        assertTrue(SurvivalPolicy.isHazardousBlock("minecraft:cactus"));
        assertTrue(SurvivalPolicy.isHazardousBlock("minecraft:soul_fire"));
        assertTrue(SurvivalPolicy.isHazardousBlock("minecraft:sweet_berry_bush"));
        assertTrue(SurvivalPolicy.isHazardousBlock("minecraft:powder_snow"));
        assertFalse(SurvivalPolicy.isHazardousBlock("minecraft:stone"));
        assertFalse(SurvivalPolicy.isHazardousBlock("minecraft:oak_planks"));
        assertFalse(SurvivalPolicy.isHazardousBlock(null));
    }

    @Test
    void eatingNeedScalesWithHealthDeficitAndCriticalStatus() {
        // Critical health triggers maximum eating urgency (95.0)
        assertEquals(95.0, SurvivalPolicy.eatingNeed(5.0f, 20.0f, 15));
        assertEquals(95.0, SurvivalPolicy.eatingNeed(2.0f, 20.0f, 20));

        // Damaged health elevates urgency above routine hunger
        double damagedNeed = SurvivalPolicy.eatingNeed(10.0f, 20.0f, 18);
        assertTrue(damagedNeed >= 85.0, "Damaged health should have elevated urgency");

        // Full health scales normally with hunger
        double fullHealthFullHunger = SurvivalPolicy.eatingNeed(20.0f, 20.0f, 20);
        assertEquals(20.0, fullHealthFullHunger);

        double fullHealthLowHunger = SurvivalPolicy.eatingNeed(20.0f, 20.0f, 8);
        assertEquals(68.0, fullHealthLowHunger);
    }

    @Test
    void strictlyExcludesMajorBosses() {
        for (String boss : SurvivalPolicy.FORBIDDEN_BOSSES) {
            SurvivalPolicy.Action action = SurvivalPolicy.evaluateThreat(boss, 3.0, 20.0f, 20.0f, fighter);
            assertNotEquals(SurvivalPolicy.Action.DEFEND, action, "Boss " + boss + " must never trigger DEFEND");
        }
    }

    @Test
    void lowHealthTriggersRetreat() {
        // Critical health with balanced personality -> retreat
        SurvivalPolicy.Action action = SurvivalPolicy.evaluateThreat("minecraft:zombie", 4.0, 5.0f, 20.0f, balanced);
        assertEquals(SurvivalPolicy.Action.RETREAT, action);

        // Low combativeness -> always retreat at low health
        SurvivalPolicy.Action timidAction = SurvivalPolicy.evaluateThreat("minecraft:zombie", 5.0, 5.0f, 20.0f, timid);
        assertEquals(SurvivalPolicy.Action.RETREAT, timidAction);
    }

    @Test
    void standardHostileEngagedByCombatant() {
        SurvivalPolicy.Action action = SurvivalPolicy.evaluateThreat("minecraft:zombie", 4.0, 20.0f, 20.0f, fighter);
        assertEquals(SurvivalPolicy.Action.DEFEND, action);
    }

    @Test
    void contextRemarksGeneratedProperly() {
        String eatRemark = SurvivalPolicy.eatingRemark("bread", balanced, 42L);
        assertNotNull(eatRemark);
        assertFalse(eatRemark.isBlank());

        String defendRemark = SurvivalPolicy.defendRemark("zombie", fighter, 100L);
        assertNotNull(defendRemark);
        assertFalse(defendRemark.isBlank());

        String retreatRemark = SurvivalPolicy.retreatRemark(timid, 77L);
        assertNotNull(retreatRemark);
        assertTrue(retreatRemark.contains("fall") || retreatRemark.contains("retreat"));
    }
}
