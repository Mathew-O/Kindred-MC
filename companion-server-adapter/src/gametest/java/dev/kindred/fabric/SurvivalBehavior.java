package dev.kindred.fabric;

import dev.kindred.core.Personality;
import dev.kindred.core.SurvivalPolicy;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.function.Consumer;

/**
 * In plain terms: an old survival prototype kept only so old tests still run. It is not part of the game.
 *
 * Technical: Retired mob prototype retained only for historical regression fixtures; never shipped or
 * dispatched. This direct-heal/equipment approximation does not qualify native player survival (E-07/F-25b).
 * Historical behavior:
 * - Evaluates inventory food to consume and heal when damaged.
 * - Defends against nearby hostile monsters with equipped weapons.
 * - Retreats safely when health is low.
 * - Strictly excludes bosses and never harms players or pets.
 */
final class SurvivalBehavior {

    private long lastEatTimeMs = 0L;
    private int attackCooldown = 0;
    private boolean weaponDrawn = false;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private long lastThreatRemarkMs = 0L;

    void tick(CompanionEntity body, Personality personality, long now, Consumer<String> speech) {
        if (body == null || !body.isAlive()) return;

        // 1. Sustenance: eat food when damaged
        tryEat(body, personality, now, speech);

        // 2. Defensive combat & tactical retreat
        tryDefend(body, personality, now, speech);
    }

    private void tryEat(CompanionEntity body, Personality personality, long now, Consumer<String> speech) {
        if (body.getHealth() >= body.getMaxHealth()) return;

        for (int i = 0; i < body.inventory().size(); i++) {
            ItemStack stack = body.inventory().getStack(i);
            if (stack.isEmpty()) continue;

            FoodComponent food = stack.get(DataComponentTypes.FOOD);
            if (food == null) continue;

            if (SurvivalPolicy.shouldEat(body.getHealth(), body.getMaxHealth(), food.nutrition(), lastEatTimeMs, now)) {
                String foodName = stack.getName().getString();
                stack.decrement(1);
                body.heal((float) food.nutrition());
                lastEatTimeMs = now;

                if (body.getWorld() != null) {
                    body.getWorld().playSound(null, body.getX(), body.getY(), body.getZ(),
                            SoundEvents.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL, 1.0f, 1.0f);
                }

                if (speech != null) {
                    speech.accept(SurvivalPolicy.eatingRemark(foodName, personality, now));
                }
                break;
            }
        }
    }

    private void tryDefend(CompanionEntity body, Personality personality, long now, Consumer<String> speech) {
        if (body.getWorld() == null) return;

        Box threatBox = body.getBoundingBox().expand(8.0);
        List<HostileEntity> nearbyHostiles = body.getWorld().getEntitiesByClass(HostileEntity.class, threatBox, HostileEntity::isAlive);

        if (nearbyHostiles.isEmpty()) {
            if (weaponDrawn) sheatheWeapon(body);
            return;
        }

        // Find closest hostile threat
        HostileEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (HostileEntity h : nearbyHostiles) {
            double distSq = body.squaredDistanceTo(h);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = h;
            }
        }

        if (closest == null) {
            if (weaponDrawn) sheatheWeapon(body);
            return;
        }

        double distance = Math.sqrt(closestDistSq);
        String entityTypeId = Registries.ENTITY_TYPE.getId(closest.getType()).toString();
        SurvivalPolicy.Action action = SurvivalPolicy.evaluateThreat(entityTypeId, distance, body.getHealth(), body.getMaxHealth(), personality);

        switch (action) {
            case RETREAT -> {
                if (weaponDrawn) sheatheWeapon(body);
                // Move in opposite direction from threat
                Vec3d away = body.getPos().subtract(closest.getPos()).normalize().multiply(4.0);
                body.getNavigation().startMovingTo(body.getX() + away.x, body.getY(), body.getZ() + away.z, 1.25);
                if (speech != null && now - lastThreatRemarkMs > 10_000L) {
                    speech.accept(SurvivalPolicy.retreatRemark(personality, now));
                    lastThreatRemarkMs = now;
                }
            }
            case DEFEND -> {
                drawWeapon(body);
                body.getLookControl().lookAt(closest, 30.0f, 30.0f);

                if (speech != null && now - lastThreatRemarkMs > 10_000L) {
                    speech.accept(SurvivalPolicy.defendRemark(closest.getName().getString(), personality, now));
                    lastThreatRemarkMs = now;
                }

                if (distance <= 2.8) {
                    if (attackCooldown <= 0) {
                        body.tryAttack(closest);
                        body.swingHand(Hand.MAIN_HAND);
                        attackCooldown = 20; // 1 second cooldown
                    } else {
                        --attackCooldown;
                    }
                } else if (body.getNavigation().isIdle()) {
                    body.getNavigation().startMovingTo(closest, 1.2);
                }
            }
            case IGNORE, EVADE -> {
                if (weaponDrawn) sheatheWeapon(body);
            }
        }
    }

    private void drawWeapon(CompanionEntity body) {
        if (weaponDrawn || body == null) return;
        previousMainHand = body.getMainHandStack().copy();

        ItemStack found = ItemStack.EMPTY;
        for (int i = 0; i < body.inventory().size(); i++) {
            ItemStack stack = body.inventory().getStack(i);
            if (!stack.isEmpty() && (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem)) {
                found = stack.copy();
                break;
            }
        }

        if (!found.isEmpty()) {
            body.equipStack(EquipmentSlot.MAINHAND, found);
        } else if (body.getMainHandStack().isEmpty()) {
            body.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
        }
        weaponDrawn = true;
    }

    private void sheatheWeapon(CompanionEntity body) {
        if (!weaponDrawn || body == null) return;
        body.equipStack(EquipmentSlot.MAINHAND, previousMainHand);
        previousMainHand = ItemStack.EMPTY;
        weaponDrawn = false;
    }
}
