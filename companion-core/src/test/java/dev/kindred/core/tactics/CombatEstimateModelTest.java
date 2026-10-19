package dev.kindred.core.tactics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class CombatEstimateModelTest {

    private final StandardCombatPredictor predictor = new StandardCombatPredictor();

    @Test
    @DisplayName("Attack charge scales correctly with attack speed and ticks")
    void attackChargeScaling() {
        // Sword (attack speed 1.6): 20 / 1.6 = 12.5 ticks
        assertEquals(13, predictor.ticksToFullCharge(1.6));
        double charge0 = predictor.calculateAttackCharge(1.6, 0);
        assertTrue(charge0 < 0.1, "0 elapsed ticks has minimal charge: " + charge0);

        double charge6 = predictor.calculateAttackCharge(1.6, 6);
        assertTrue(charge6 > 0.45 && charge6 < 0.55, "6 elapsed ticks is ~half charge: " + charge6);

        double charge12 = predictor.calculateAttackCharge(1.6, 12);
        assertEquals(1.0, charge12, 0.01, "12 elapsed ticks reaches full charge: " + charge12);

        // Axe (attack speed 0.9): 20 / 0.9 = 22.22 ticks
        assertEquals(23, predictor.ticksToFullCharge(0.9));
        double axeCharge5 = predictor.calculateAttackCharge(0.9, 5);
        assertTrue(axeCharge5 > 0.2 && axeCharge5 < 0.3, "5 elapsed ticks on axe is low charge: " + axeCharge5);
    }

    @Test
    @DisplayName("Critical hit multiplier applies 1.5x only to base damage, not enchantment bonus")
    void criticalMultiplierAppliesToBaseOnly() {
        // Weapon with base 6.0 and Sharpness I (+1.0 damage)
        var weapon = new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250,
                Map.of("sharpness", 1), false, true);

        var target = new MobThreatObservation(UUID.randomUUID(), "minecraft:creeper", 2.0, 20.0, 20.0,
                0, 0.0, false, false, true, 30, false, 20, false);

        // Normal strike at full charge: base 6.0 + sharpness 1.0 = 7.0
        var normalDmg = predictor.estimateStrikeDamage(weapon, target, 1.0, false);
        assertEquals(7.0, normalDmg.minDamage(), 0.01);

        // Critical strike at full charge: base (6.0 * 1.5 = 9.0) + sharpness (1.0) = 10.0
        // (NOT 7.0 * 1.5 = 10.5!)
        var critDmg = predictor.estimateStrikeDamage(weapon, target, 1.0, true);
        assertEquals(10.0, critDmg.minDamage(), 0.01,
                "Critical multiplier must apply 1.5x to base damage portion only, before adding enchantment bonus");
    }

    @Test
    @DisplayName("Smite enchantment contributes damage only to undead mobs")
    void smiteEnchantmentTargetSensitivity() {
        // Weapon with Smite II (+5.0 damage against undead)
        var smiteWeapon = new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250,
                Map.of("smite", 2), false, true);

        var undeadTarget = new MobThreatObservation(UUID.randomUUID(), "minecraft:zombie", 2.0, 20.0, 20.0,
                0, 0.0, true, false, true, -1, false, 20, false);

        var nonUndeadTarget = new MobThreatObservation(UUID.randomUUID(), "minecraft:creeper", 2.0, 20.0, 20.0,
                0, 0.0, false, false, true, 30, false, 20, false);

        var undeadDamage = predictor.estimateStrikeDamage(smiteWeapon, undeadTarget, 1.0, false);
        assertEquals(11.0, undeadDamage.minDamage(), 0.01, "Smite II adds 5.0 damage to undead (6.0 + 5.0 = 11.0)");

        var nonUndeadDamage = predictor.estimateStrikeDamage(smiteWeapon, nonUndeadTarget, 1.0, false);
        assertEquals(6.0, nonUndeadDamage.minDamage(), 0.01, "Smite II adds 0.0 damage to non-undead");
    }

    @Test
    @DisplayName("Vanilla armor mitigation correctly reduces incoming damage")
    void armorMitigationFormula() {
        // Unarmored: 10.0 damage deals 10.0
        double unarmored = StandardCombatPredictor.calculateMitigatedDamage(10.0, 0, 0.0);
        assertEquals(10.0, unarmored, 0.01);

        // Zombie with armor 2, toughness 0:
        // defense = clamp(2 - 10/(2 + 0), 2/5, 20) = clamp(-3, 0.4, 20) = 0.4
        // mitigated = 10 * (1 - 0.4/25) = 10 * 0.984 = 9.84
        double zombieMitigated = StandardCombatPredictor.calculateMitigatedDamage(10.0, 2, 0.0);
        assertEquals(9.84, zombieMitigated, 0.01);

        // Heavily armored target: armor 20, toughness 8, damage 10
        // reduction = 20 - 10 / (2 + 2) = 20 - 2.5 = 17.5
        // defense = 17.5
        // mitigated = 10 * (1 - 17.5 / 25) = 10 * (1 - 0.70) = 3.0
        double heavyMitigated = StandardCombatPredictor.calculateMitigatedDamage(10.0, 20, 8.0);
        assertEquals(3.0, heavyMitigated, 0.01);
    }

    @Test
    @DisplayName("Axe-to-sword weapon switch resets attack charge in sequence evaluation")
    void weaponSwitchResetsAttackCharge() {
        UUID zombieId = UUID.randomUUID();
        var zombie = new MobThreatObservation(zombieId, "minecraft:zombie", 2.0, 20.0, 20.0,
                0, 0.0, true, false, true, -1, false, 20, false);

        var swordProfile = new WeaponProfile(1, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true);

        // Observation where player currently holds an axe in slot 0
        var obsHoldingAxe = new TacticalObservation(
                UUID.randomUUID(), 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20,
                0, "minecraft:iron_axe",
                List.of(swordProfile),
                List.of(zombie),
                true, false, false, false
        );

        // Switch to sword and strike immediately (switch = 1 tick, strike = 1 tick -> total 2 ticks)
        var switchAndStrikeSeq = TacticalSequence.pair(ActionStep.PREPARE_WEAPON, ActionStep.NORMAL_STRIKE, zombieId, swordProfile);
        var switchOutcome = predictor.evaluateSequence(obsHoldingAxe, switchAndStrikeSeq);

        // At 2 ticks on a 1.6 speed weapon, charge is low (~0.2), so dealt damage is small fraction of 6.0 (~1.3)
        assertTrue(switchOutcome.dealtDamage().minDamage() < 2.5,
                "Immediate strike after weapon switch deals severely reduced damage due to charge reset: " + switchOutcome.dealtDamage().minDamage());

        // Same strike when sword was ALREADY held in slot 1 (no switch)
        var obsHoldingSword = new TacticalObservation(
                UUID.randomUUID(), 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20,
                1, "minecraft:iron_sword",
                List.of(swordProfile),
                List.of(zombie),
                true, false, false, false
        );

        var normalStrikeSeq = TacticalSequence.single(ActionStep.NORMAL_STRIKE, zombieId, swordProfile);
        var normalOutcome = predictor.evaluateSequence(obsHoldingSword, normalStrikeSeq);
        assertEquals(6.0, normalOutcome.dealtDamage().minDamage(), 0.01,
                "Strike with already-held weapon deals full 6.0 damage");
    }

    @Test
    @DisplayName("Creeper fuse expiration catches player in explosion if sequence does not kill or escape in time")
    void creeperFuseDeadlineEnforcement() {
        UUID creeperId = UUID.randomUUID();
        // Creeper with only 5 ticks remaining before explosion!
        var imminentCreeper = new MobThreatObservation(creeperId, "minecraft:creeper", 3.0, 20.0, 20.0,
                0, 0.0, false, false, true, 5, true, 5, false);

        var sword = new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true);

        var obs = new TacticalObservation(
                UUID.randomUUID(), 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20,
                0, "minecraft:iron_sword",
                List.of(sword),
                List.of(imminentCreeper),
                true, false, false, false
        );

        // A critical strike takes 9 ticks, which is > 5 ticks! Creeper is not one-shotted (only 20 HP vs ~9 dmg)
        var critSeq = TacticalSequence.single(ActionStep.CRITICAL_STRIKE, creeperId, sword);
        var critOutcome = predictor.evaluateSequence(obs, critSeq);

        assertTrue(critOutcome.receivedSelfDamage().maxDamage() >= 20.0,
                "Failing to kill creeper before 5-tick fuse expires results in explosion self-damage");
        assertEquals(SurvivalClass.EMERGENCY_ONLY, critOutcome.survivalClass(),
                "Sequence caught in explosion cannot be SURVIVABLE");
    }

    @Test
    @DisplayName("Unknown or custom enchantments produce SurvivalClass.UNKNOWN and refuse precise kill predictions")
    void unknownEnchantmentsProduceUnknownSurvivalClass() {
        // Weapon with unsupported / custom enchantment
        var customWeapon = new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250,
                Map.of("modded:magic_strike", 3), false, true);
        assertTrue(customWeapon.hasUnknownEnchantments(), "Weapon correctly detects unknown enchantments");

        var zombie = new MobThreatObservation(UUID.randomUUID(), "minecraft:zombie", 2.0, 5.0, 20.0,
                0, 0.0, true, false, true, -1, false, 20, false);

        var obs = new TacticalObservation(
                UUID.randomUUID(), 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20,
                0, "minecraft:iron_sword",
                List.of(customWeapon),
                List.of(zombie),
                true, false, false, false
        );

        var seq = TacticalSequence.single(ActionStep.NORMAL_STRIKE, zombie.entityId(), customWeapon);
        var outcome = predictor.evaluateSequence(obs, seq);

        assertEquals(SurvivalClass.UNKNOWN, outcome.survivalClass(),
                "Unknown enchantment must yield SurvivalClass.UNKNOWN");
        assertFalse(outcome.killsTargetReliably(),
                "Unknown enchantment must not produce guaranteed reliable kill");
    }

    @Test
    @DisplayName("Critical strike restriction: when canPerformCritical is false (e.g. water/climbing/blind), crit multiplier does not apply")
    void criticalStrikeConditionsRevokedWhenCanPerformCriticalIsFalse() {
        var sword = new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true);
        var zombie = new MobThreatObservation(UUID.randomUUID(), "minecraft:zombie", 2.0, 20.0, 20.0,
                0, 0.0, true, false, true, -1, false, 20, false);

        // Player restricted (e.g. in water or climbing or blind) -> canPerformCritical = false
        var obsRestricted = new TacticalObservation(
                UUID.randomUUID(), 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20,
                0, "minecraft:iron_sword",
                List.of(sword),
                List.of(zombie),
                true, false, false, false,
                false // canPerformCritical = false!
        );

        var critSeq = TacticalSequence.single(ActionStep.CRITICAL_STRIKE, zombie.entityId(), sword);
        var outcome = predictor.evaluateSequence(obsRestricted, critSeq);

        assertEquals(6.0, outcome.dealtDamage().minDamage(), 0.01,
                "Restricted player attempting critical strike deals only standard 6.0 damage without 1.5x multiplier");
    }

    @Test
    @DisplayName("Adjacent strikes within 10 ticks are non-additive due to vanilla recent-hit i-frame rules")
    void multiStrikeAdjacentHitsWithin10TicksAreNonAdditiveDueToIFrames() {
        var sword = new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true);
        var target = new MobThreatObservation(UUID.randomUUID(), "minecraft:zombie", 2.0, 20.0, 20.0,
                0, 0.0, true, false, true, -1, false, 20, false);

        var obs = new TacticalObservation(
                UUID.randomUUID(), 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20,
                0, "minecraft:iron_sword",
                List.of(sword),
                List.of(target),
                true, false, false, false
        );

        // Two normal strikes in succession (1 tick + 1 tick = 2 ticks apart, < 10 ticks i-frames)
        var twoStrikeSeq = TacticalSequence.pair(ActionStep.NORMAL_STRIKE, ActionStep.NORMAL_STRIKE, target.entityId(), sword);
        var outcome = predictor.evaluateSequence(obs, twoStrikeSeq);

        // First strike dealt 6.0 damage. Second strike at low charge deals ~1.3 damage, which is < 6.0.
        // Therefore, the second strike within the 10-tick i-frame window adds 0 additional damage!
        assertEquals(6.0, outcome.dealtDamage().minDamage(), 0.01,
                "Second strike within 10 ticks with lower damage adds 0 damage due to i-frames");
    }

    @Test
    @DisplayName("Creeper forced ignition explodes even if player retreats")
    void creeperForcedIgnitionExplodesDespiteRetreatAttempt() {
        UUID creeperId = UUID.randomUUID();
        // Ignited creeper with 6 ticks remaining
        var ignitedCreeper = new MobThreatObservation(creeperId, "minecraft:creeper", 3.0, 20.0, 20.0,
                0, 0.0, false, false, true, 6, true, 6, false);

        var sword = new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true);

        var obs = new TacticalObservation(
                UUID.randomUUID(), 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20,
                0, "minecraft:iron_sword",
                List.of(sword),
                List.of(ignitedCreeper),
                true, false, false, false
        );

        // Sequence: Retreat (8 ticks). Fuse is 6 ticks!
        var retreatSeq = TacticalSequence.single(ActionStep.RETREAT_TO_SAFE_CORRIDOR, null, null);
        var outcome = predictor.evaluateSequence(obs, retreatSeq);

        assertTrue(outcome.receivedSelfDamage().maxDamage() >= 20.0,
                "Forced ignition creeper explodes at 6 ticks during 8-tick retreat");
        assertEquals(SurvivalClass.EMERGENCY_ONLY, outcome.survivalClass());
    }

    @Test
    @DisplayName("Multiple enemies damage is accumulated across all observed hostiles")
    void multipleEnemiesDamageAggregatedInEvaluation() {
        var zombie = new MobThreatObservation(UUID.randomUUID(), "minecraft:zombie", 1.5, 20.0, 20.0,
                2, 0.0, true, false, true, -1, false, 2, false);
        var skeleton = new MobThreatObservation(UUID.randomUUID(), "minecraft:skeleton", 8.0, 20.0, 20.0,
                0, 0.0, true, false, true, -1, false, 2, false);

        var sword = new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true);

        var obs = new TacticalObservation(
                UUID.randomUUID(), 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20,
                0, "minecraft:iron_sword",
                List.of(sword),
                List.of(zombie, skeleton),
                true, false, false, false
        );

        // Strike zombie (takes 1 tick, but if target is not killed or during sequence, both attack)
        // A 3-step sequence taking 10 ticks:
        var multiStepSeq = TacticalSequence.triple(ActionStep.NORMAL_STRIKE, ActionStep.STAY_AND_GUARD, ActionStep.RETREAT_TO_SAFE_CORRIDOR, zombie.entityId(), sword);
        var outcome = predictor.evaluateSequence(obs, multiStepSeq);

        // Both zombie (3.0) and skeleton (4.0) attack during the 10-tick sequence -> 7.0 self-damage
        assertTrue(outcome.receivedSelfDamage().maxDamage() >= 7.0,
                "Both zombie and skeleton damage must be aggregated: " + outcome.receivedSelfDamage().maxDamage());
    }
}
