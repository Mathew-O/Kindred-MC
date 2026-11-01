package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.tactics.CombatRuleset;
import dev.kindred.core.tactics.DamageModel;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a GameTest: in a real test world, the player's body is checked to follow the game's combat
 * rules tick by tick as an ordinary player's does: a sword swing charges up over time, a raised shield blocks
 * only after five ticks, an axe hit on the shield puts it out of use for a hundred ticks, a second hit soon
 * after the first lands only what it exceeds the first by, and a strike reaches three blocks and no further.
 * A last test checks the player judges a mob's health only from the hits it saw land, not from the server's
 * true number.
 *
 * Technical: The native body against the combat ruleset (E-41a): its own tick must drive the mechanics a
 * player's does. These fixtures check that a swing charges, a raised shield comes into use and an axe's
 * disabling wears off tick by tick as {@link CombatRuleset} says, that damage immunity holds a second hit to
 * its excess, and that a strike reaches as far as a client's and no further. Vanilla's own code computes each
 * outcome; what is under test is the body ticking as a player's would, and the adapter's own limits.
 */
public final class NativeRulesetGameTests implements FabricGameTest {
    /** An iron sword swings 1.6 times a second: the charge after each tick of the body's is the ruleset's. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rulesetCharge", tickLimit = 300)
    public void aSwingChargesTickByTickAsTheRulesetSays(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        long[] start = {-1};
        int[] checked = {0};
        // A step that fails is only tried again next tick: a wrong charge on one tick is kept, and fails the test at the end.
        String[] wrong = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Swinger"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.player().getInventory().selectedSlot = 0;
                })
                // The sword's attack speed applies once the body has ticked with it in hand.
                .createAndAdd(() -> context.assertTrue(Math.abs(f.player().getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED) - 1.6) < 1e-6,
                        "An iron sword's attack speed: " + f.player().getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED)))
                .createAndAdd(() -> {
                    f.player().resetLastAttackedTicks();
                    start[0] = f.runtime.nativeBody(f.id).orElseThrow().ticksRun();
                })
                .createAndAdd(() -> {
                    int ticks = (int) (f.runtime.nativeBody(f.id).orElseThrow().ticksRun() - start[0]);
                    float charge = f.player().getAttackCooldownProgress(0.5f);
                    float expected = CombatRuleset.charge(ticks, f.player().getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED));
                    if (Math.abs(charge - expected) >= 1e-6 && wrong[0] == null) wrong[0] = "after " + ticks + " ticks the charge is " + charge + ", not " + expected;
                    checked[0]++;
                    context.assertTrue(ticks >= 14, "Charging");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Swinger: the charge checked at {} ticks, {}; strong after {}", checked[0], wrong[0] == null ? "all as the ruleset says" : wrong[0],
                            CombatRuleset.ticksToStrong(1.6));
                    context.assertTrue(wrong[0] == null, "The charge: " + wrong[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A shield raised by the body's skill blocks from its fifth tick in use, and not before. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rulesetShield", tickLimit = 300)
    public void aShieldBlocksOnlyOnceHeldUpFiveTicks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        long[] raised = {-1};
        int[] before = {0}, after = {0};
        String[] wrong = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Raiser"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    context.assertTrue(f.runtime.nativeBlock(f.source, f.id, 40) == 1, "Block requested");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    long now = f.runtime.nativeBody(f.id).orElseThrow().ticksRun();
                    if (raised[0] < 0) {
                        context.assertTrue(player.isUsingItem(), "The shield going up");
                        raised[0] = now - player.getItemUseTime();
                    }
                    long up = now - raised[0];
                    if (player.getItemUseTime() != up && wrong[0] == null) wrong[0] = "in use " + player.getItemUseTime() + " ticks, raised " + up + " body ticks ago";
                    boolean blocking = player.isBlocking();
                    if (blocking != (up >= CombatRuleset.SHIELD_RAISE_TICKS) && wrong[0] == null) wrong[0] = "blocking " + blocking + " after " + up + " ticks";
                    if (blocking) after[0]++; else before[0]++;
                    context.assertTrue(up >= 12, "Held up");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Raiser: not blocking for {} ticks, then blocking for {}", before[0], after[0]);
                    context.assertTrue(wrong[0] == null, "The shield: " + wrong[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A zombie's axe on the body's raised shield puts it out of use for a hundred of the body's ticks, during which the
     * body cannot raise it again, as a player cannot.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rulesetAxe", tickLimit = 600)
    public void anAxeOnARaisedShieldPutsItOutOfUseForAHundredTicks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        long[] disabled = {-1};
        int[] raisedWhileOut = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Guarded"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                    // Kept alive through the hits that land while the shield is not yet up, or turned away, until one
                    // lands on it: Resistance V takes all the damage, and leaves the shield's blocking and disabling as
                    // they are. One run died to those hits first and never saw the shield struck.
                    player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.RESISTANCE,
                            1200, 4, false, false));
                    player.setYaw(0);
                    player.setHeadYaw(0);
                    // Spawned with its goals (the test context's spawnMob clears them): it walks up and swings.
                    var world = context.getWorld();
                    zombie[0] = EntityType.ZOMBIE.create(world);
                    var at = context.getAbsolute(new Vec3d(3.5, 2.0, 5.5));
                    zombie[0].refreshPositionAndAngles(at.x, at.y, at.z, 180, 0);
                    zombie[0].setPersistent();
                    world.spawnEntity(zombie[0]);
                    zombie[0].equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
                    zombie[0].equipStack(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN)); // No burning by day.
                    zombie[0].setTarget(player);
                    context.assertTrue(f.runtime.nativeBlock(f.source, f.id, 40) == 1, "Block requested");
                })
                .createAndAdd(() -> {
                    // Up again until the axe lands.
                    if (!f.player().getItemCooldownManager().isCoolingDown(Items.SHIELD)
                            && f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("IDLE")) f.runtime.nativeBlock(f.source, f.id, 40);
                    context.assertTrue(f.player().getItemCooldownManager().isCoolingDown(Items.SHIELD), "The axe has struck the raised shield");
                    disabled[0] = f.runtime.nativeBody(f.id).orElseThrow().ticksRun();
                    context.assertTrue(!f.player().isBlocking(), "The shield is down");
                    zombie[0].discard();
                })
                .createAndAdd(() -> {
                    // While it is out of use the body cannot raise it, as vanilla refuses a player: asked to hold it up
                    // longer than a shield takes to block, it never blocks.
                    if (f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("IDLE")) f.runtime.nativeBlock(f.source, f.id, 20);
                    if (f.player().isBlocking() && f.player().getItemCooldownManager().isCoolingDown(Items.SHIELD)) raisedWhileOut[0]++;
                    context.assertTrue(!f.player().getItemCooldownManager().isCoolingDown(Items.SHIELD), "Still out of use");
                })
                .createAndAdd(() -> {
                    long ticks = f.runtime.nativeBody(f.id).orElseThrow().ticksRun() - disabled[0];
                    KindredMod.LOGGER.info("Guarded: the shield was out of use {} body ticks, blocking with it {} of them", ticks, raisedWhileOut[0]);
                    context.assertTrue(raisedWhileOut[0] == 0, "Raised and blocking while out of use: " + raisedWhileOut[0] + " ticks");
                    context.assertTrue(Math.abs(ticks - CombatRuleset.SHIELD_DISABLED_TICKS) <= 2, "Out of use " + ticks + " ticks");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Damage immunity: a weak hit, then a stronger one five ticks later, takes from a pig only the first and the second's
     * excess over it, as the ruleset has it; ten ticks after, a hit lands whole.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rulesetImmune", tickLimit = 300)
    public void aSecondHitWithinTheImmunityLandsOnlyItsExcess(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        PigEntity[] pig = new PigEntity[1];
        float[] health = new float[3];
        double[] dealt = new double[2];
        long[] at = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Striker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.player().getInventory().selectedSlot = 0;
                    pig[0] = context.spawnMob(EntityType.PIG, new Vec3d(3.5, 2.0, 5.0));
                    pig[0].setAiDisabled(true);
                })
                .createAndAdd(() -> context.assertTrue(Math.abs(f.player().getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED) - 1.6) < 1e-6, "Sword in hand"))
                .createAndAdd(() -> {
                    // A swing at once, hardly charged.
                    var player = f.player();
                    player.resetLastAttackedTicks();
                    health[0] = pig[0].getHealth();
                    dealt[0] = DamageModel.hit(new DamageModel.Weapon(DamageModel.Kind.SWORD, DamageModel.Tier.IRON, 0), player.getAttackCooldownProgress(0.5f), false, 0, 0);
                    player.attack(pig[0]);
                    at[0] = f.runtime.nativeBody(f.id).orElseThrow().ticksRun();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeBody(f.id).orElseThrow().ticksRun() - at[0] >= 5, "Five ticks on"))
                .createAndAdd(() -> {
                    var player = f.player();
                    health[1] = pig[0].getHealth();
                    dealt[1] = DamageModel.hit(new DamageModel.Weapon(DamageModel.Kind.SWORD, DamageModel.Tier.IRON, 0), player.getAttackCooldownProgress(0.5f), false, 0, 0);
                    player.attack(pig[0]);
                    health[2] = pig[0].getHealth();
                    int since = (int) (f.runtime.nativeBody(f.id).orElseThrow().ticksRun() - at[0]);
                    double first = health[0] - health[1], second = health[1] - health[2];
                    KindredMod.LOGGER.info("Striker: {} then {} dealt {} ticks apart, {} and {} landed", dealt[0], dealt[1], since, first, second);
                    context.assertTrue(Math.abs(first - dealt[0]) < 1e-3, "The first hit whole: " + first + " of " + dealt[0]);
                    context.assertTrue(dealt[1] > dealt[0], "The second hit is the larger");
                    context.assertTrue(Math.abs(second - CombatRuleset.landed(dealt[1], dealt[0], since)) < 1e-3,
                            "The second only by its excess: " + second + ", the ruleset " + CombatRuleset.landed(dealt[1], dealt[0], since));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A strike reaches three blocks from the eyes to the target's hitbox, as a client's does: a pig 2.65 off is hit; one
     * 3.43 off (3.8 between their feet, inside the four blocks the body used to allow) is out of reach and unharmed.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rulesetReach", tickLimit = 400)
    public void aStrikeReachesThreeBlocksToTheHitboxAndNoFurther(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        PigEntity[] near = new PigEntity[1], far = new PigEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Reacher"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().selectedSlot = 0;
                    // The player stands at (3.5, 2, 3.5): first a pig 3.8 on, then one 3.0 on.
                    far[0] = context.spawnMob(EntityType.PIG, new Vec3d(3.5, 2.0, 7.3));
                    far[0].setAiDisabled(true);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    double reach = Math.sqrt(far[0].getBoundingBox().squaredMagnitude(player.getEyePos()));
                    context.assertTrue(reach > CombatRuleset.ENTITY_REACH && player.squaredDistanceTo(far[0]) < 16.0,
                            "The far pig beyond a client's reach, inside the old rule's: " + reach + ", " + Math.sqrt(player.squaredDistanceTo(far[0])));
                    context.assertTrue(f.runtime.nativeAttack(f.source, f.id, far[0].getUuid(), 0) == 1, "Strike at the far pig requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && !activity.work().startsWith("Completed native_attack"), "Refused: " + activity.work());
                    context.assertTrue(far[0].getHealth() == far[0].getMaxHealth(), "The far pig unharmed");
                    near[0] = context.spawnMob(EntityType.PIG, new Vec3d(3.5, 2.0, 6.5));
                    near[0].setAiDisabled(true);
                    far[0].discard();
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    double reach = Math.sqrt(near[0].getBoundingBox().squaredMagnitude(player.getEyePos()));
                    context.assertTrue(reach < CombatRuleset.ENTITY_REACH, "The near pig within reach: " + reach);
                    context.assertTrue(f.runtime.nativeAttack(f.source, f.id, near[0].getUuid(), 0) == 1, "Strike at the near pig requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_attack"), "Struck: " + activity.work());
                    context.assertTrue(near[0].getHealth() < near[0].getMaxHealth(), "The near pig hit");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Health as a player can know it (E-41a). A zombie hurt to ten by something the player never saw is believed whole,
     * at its kind's twenty. After the player's own sword hit lands it is believed at twenty less what that hit dealt, as
     * the ruleset works it out, while the server knows it is lower. The tactical observer sees the belief, not the number.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rulesetBelief", tickLimit = 300)
    public void theHealthItBelievesIsWorkedOutFromTheHitsItSawLand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Believer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().selectedSlot = 0;
                    zombie[0] = context.spawnMob(EntityType.ZOMBIE, new Vec3d(3.5, 2.0, 5.5));
                    zombie[0].setHealth(10);
                    // Anything on its head keeps it from burning by day (a carved pumpkin adds no armour). A burn the
                    // fixture does not control hurt it just before a strike, inside the damage immunity, so the strike
                    // showed no hurt flash and was rightly not believed; or after it, so the health lost was not the hit.
                    zombie[0].equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
                    double believed = NativePlayerBody.believedHealth(player, zombie[0]);
                    context.assertTrue(believed == 20, "Hurt by nothing it saw, believed whole: " + believed);
                    var seen = NativeTacticalObserver.observe(player, f.id, 0, false, false, context.getWorld().getTime());
                    context.assertTrue(seen.observedMobs().size() == 1 && seen.observedMobs().getFirst().currentHealth() == 20,
                            "The observer sees the belief: " + seen.observedMobs());
                    context.assertTrue(f.runtime.nativeAttack(f.source, f.id, zombie[0].getUuid(), 0) == 1, "Strike requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_attack"), "Struck: " + activity.work());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    double truth = zombie[0].getHealth(), dealt = 10 - truth;
                    double believed = NativePlayerBody.believedHealth(player, zombie[0]);
                    var seen = NativeTacticalObserver.observe(player, f.id, 0, false, false, context.getWorld().getTime());
                    KindredMod.LOGGER.info("Believer: the hit dealt {}; believed {}, the server's {}", dealt, believed, truth);
                    context.assertTrue(dealt > 0, "The hit landed");
                    context.assertTrue(Math.abs(believed - (20 - dealt)) < 1e-3, "Believed at twenty less the hit as the ruleset works it out: " + believed);
                    context.assertTrue(Math.abs(believed - truth) > 5, "Not the server's number: " + believed + " against " + truth);
                    context.assertTrue(Math.abs(seen.observedMobs().getFirst().currentHealth() - believed) < 1e-3, "The observer sees the belief");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
