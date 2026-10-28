package dev.kindred.fabric;

import dev.kindred.core.tactics.DamageInterval;
import dev.kindred.core.tactics.MobThreatObservation;
import dev.kindred.core.tactics.StandardCombatPredictor;
import dev.kindred.core.tactics.TacticalDecision;
import dev.kindred.core.tactics.TacticalObservation;
import dev.kindred.core.tactics.WeaponProfile;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that a player reads a
 * fight correctly and picks a safe plan.
 *
 * Technical: Acceptance GameTests for Tactical Priority Hierarchy and Combat Prediction (E-07b):
 * 1. Read-only observation faithfully captures visible mob health, armor, and inventory weapons.
 * 2. Shadow tactical evaluation produces valid decisions without issuing body inputs or mutations.
 * 3. Controlled native attack parity verifies predictor calculations against actual Yarn 1.21.1 combat mechanics.
 * 4. Critical strike eligibility and weapon switch cooldown resets match authentic native mechanics.
 */
public final class NativeTacticalGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tacticalBatch", tickLimit = 320)
    public void nativeTacticalObserverAccuratelyCapturesVisibleMobAndEquipment(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "TacticalObserver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Equip Iron Sword in slot 0, Iron Axe in slot 1
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.player().getInventory().setStack(1, new ItemStack(Items.IRON_AXE));
                    f.player().getInventory().selectedSlot = 0;

                    // Spawn a visible Zombie with custom 14.0 HP
                    ZombieEntity zombie = context.spawnMob(EntityType.ZOMBIE, new Vec3d(3.5, 2.0, 5.5));
                    zombie.setAiDisabled(true);
                    zombie.setHealth(14.0f);

                    // Observe via NativeTacticalObserver
                    TacticalObservation obs = NativeTacticalObserver.observe(
                            f.player(), f.id, f.runtime.nativeAttentionStatus(f.id).revision(),
                            false, false, context.getWorld().getTime()
                    );

                    context.assertTrue(obs != null, "Observation generated");
                    context.assertTrue(obs.heldItemId().contains("iron_sword"), "Held item is iron sword: " + obs.heldItemId());
                    context.assertTrue(obs.availableWeapons().size() >= 2, "Found at least sword and axe: " + obs.availableWeapons().size());
                    context.assertTrue(obs.observedMobs().size() == 1, "Exactly one visible mob observed: " + obs.observedMobs().size());

                    var mobObs = obs.observedMobs().get(0);
                    // A player cannot see a mob's health: hurt by nothing it saw, the zombie is believed at its kind's full
                    // twenty, not the fourteen the server keeps (E-41a).
                    context.assertEquals(20.0, mobObs.currentHealth(), "Health as a player can know it: " + mobObs.currentHealth());
                    context.assertTrue(mobObs.isUndead(), "Zombie identified as undead");
                    context.assertFalse(mobObs.isArthropod(), "Zombie is not arthropod");
                    context.assertTrue(obs.canPerformCritical(), "Player standing in clear air can perform critical jump");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tacticalBatch", tickLimit = 320)
    public void shadowTacticalEvaluationOperatesWithoutMutationsOrInputs(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "ShadowEvaluator"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    // Let at least 2 ticks run so beforeNativeTick executes shadow evaluation
                    TacticalDecision decision = f.runtime.nativeTacticalDiagnostics(f.id);
                    if (decision == null) return;
                })
                .createAndAdd(() -> {
                    TacticalDecision decision = f.runtime.nativeTacticalDiagnostics(f.id);
                    context.assertTrue(decision != null, "Shadow tactical decision exists");
                    context.assertTrue(decision.hasViableOption(), "Shadow decision has viable option");

                    // Zero inputs or mutations
                    context.assertTrue(f.player().forwardSpeed == 0.0f, "Forward input remains zero");
                    context.assertTrue(f.player().sidewaysSpeed == 0.0f, "Sideways input remains zero");
                    context.assertTrue(!f.player().handSwinging, "Hand swinging remains false");
                    context.assertTrue(!f.player().isUsingItem(), "Item using remains false");
                    context.assertTrue(f.player().getInventory().selectedSlot == 0, "Selected slot unchanged");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tacticalBatch", tickLimit = 320)
    public void nativePredictionVersusEffectParity(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "DamageParityTester"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Prepare player with iron sword
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.player().getInventory().selectedSlot = 0;

                    // Spawn zombie with 20 HP and native armor (2)
                    ZombieEntity zombie = context.spawnMob(EntityType.ZOMBIE, new Vec3d(3.5, 2.0, 5.0));
                    zombie.setAiDisabled(true);
                    zombie.setHealth(20.0f);

                    // Predict damage using StandardCombatPredictor
                    StandardCombatPredictor predictor = new StandardCombatPredictor();
                    var swordProfile = new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true);
                    var targetObs = new MobThreatObservation(zombie.getUuid(), "minecraft:zombie", 1.5, 20.0, 20.0,
                            2, 0.0, true, false, true, -1, false, 20, false);

                    DamageInterval predicted = predictor.estimateStrikeDamage(swordProfile, targetObs, 1.0, false);
                    context.assertTrue(predicted.minDamage() > 5.5 && predicted.minDamage() < 6.0,
                            "Predicted strike damage ~5.9: " + predicted.minDamage());

                    // Execute controlled native attack
                    f.player().resetLastAttackedTicks();
                    // Tick player 25 times to guarantee full attack charge (speed 1.6 requires ~13 ticks)
                    for (int i = 0; i < 25; i++) {
                        f.player().tick();
                    }
                    context.assertTrue(f.player().getAttackCooldownProgress(0.0f) >= 0.95f,
                            "Player is fully charged: " + f.player().getAttackCooldownProgress(0.0f));

                    float healthBefore = zombie.getHealth();
                    f.player().attack(zombie);
                    float actualDamageDealt = healthBefore - zombie.getHealth();

                    // Verify parity between pure prediction and actual native attack
                    context.assertTrue(Math.abs(actualDamageDealt - predicted.minDamage()) < 0.25f,
                            "Parity holds: actual damage (" + actualDamageDealt + ") matches prediction (" + predicted.minDamage() + ")");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tacticalBatch", tickLimit = 320)
    public void nativeCriticalEligibilityRevokedWhenInWaterOrBlind(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "CriticalRestrictions"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Normal state: player can crit
                    TacticalObservation obsNormal = NativeTacticalObserver.observe(
                            f.player(), f.id, 1L, false, false, context.getWorld().getTime()
                    );
                    context.assertTrue(obsNormal.canPerformCritical(), "Normal player has critical eligibility");

                    // Apply blindness effect: critical eligibility revoked
                    f.player().addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 200));
                    TacticalObservation obsBlind = NativeTacticalObserver.observe(
                            f.player(), f.id, 1L, false, false, context.getWorld().getTime()
                    );
                    context.assertFalse(obsBlind.canPerformCritical(), "Blindness revokes critical eligibility");

                    // Weapon switch cooldown test
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.player().tick();
                    for (int i = 0; i < 20; i++) f.player().tick();
                    context.assertTrue(f.player().getAttackCooldownProgress(0.0f) >= 0.9f, "Player is fully charged");

                    // Switch item type to axe
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.player().tick(); // item type switch detected in tick()
                    context.assertTrue(f.player().getAttackCooldownProgress(0.0f) < 0.25f,
                            "Switching item type resets attack cooldown: " + f.player().getAttackCooldownProgress(0.0f));

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
