package dev.kindred.fabric;

import dev.kindred.core.tactics.MobThreatObservation;
import dev.kindred.core.tactics.TacticalObservation;
import dev.kindred.core.tactics.WeaponProfile;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure adapter observer that translates native Minecraft 1.21.1 entity and player state into
 * immutable TacticalObservation records.
 *
 * Strict read-only boundaries:
 * - Only queries visible, loaded mobs using line of sight. Unseen mobs behind walls are excluded.
 * - Protects player privacy: other players' health/equipment are excluded from mob threat queries.
 * - Read-only: does not modify entity health, player inventory, cooldowns, or RNG.
 */
public final class NativeTacticalObserver {

    private NativeTacticalObserver() { }

    public static TacticalObservation observe(
            ServerPlayerEntity player,
            java.util.UUID companionId,
            long bodyGeneration,
            boolean foodTaskAuthorized,
            boolean shelterProtectionAuthorized,
            long serverTick
    ) {
        return observe(player, companionId, bodyGeneration, foodTaskAuthorized, shelterProtectionAuthorized, serverTick, null);
    }

    public static TacticalObservation observe(
            ServerPlayerEntity player,
            java.util.UUID companionId,
            long bodyGeneration,
            boolean foodTaskAuthorized,
            boolean shelterProtectionAuthorized,
            long serverTick,
            List<WeaponProfile> cachedWeapons
    ) {
        var world = player.getServerWorld();
        BlockPos feetPos = player.getBlockPos();

        // 1. Critical strike physical eligibility
        boolean canPerformCritical = !player.isTouchingWater()
                && !player.isClimbing()
                && !player.hasStatusEffect(StatusEffects.BLINDNESS)
                && !player.hasVehicle()
                && !world.getBlockState(feetPos.up(2)).isSolidBlock(world, feetPos.up(2));

        List<WeaponProfile> weapons = cachedWeapons != null ? cachedWeapons : scanWeaponProfiles(player);

        // 3. Scan visible loaded mobs within 16 blocks (strictly excluding players to preserve privacy)
        List<LivingEntity> nearbyLiving = new ArrayList<>();
        world.collectEntitiesByType(
                TypeFilter.instanceOf(LivingEntity.class),
                player.getBoundingBox().expand(16.0),
                // Threats only: monsters, or any mob currently targeting this player. Grazing animals are not danger.
                e -> e != player && e.isAlive() && !(e instanceof PlayerEntity)
                        && (e instanceof net.minecraft.entity.mob.Monster
                        || e instanceof net.minecraft.entity.mob.MobEntity hunter && hunter.getTarget() == player),
                nearbyLiving,
                16
        );

        List<MobThreatObservation> mobObservations = new ArrayList<>();
        for (LivingEntity mob : nearbyLiving) {
            if (!loadedSightline(player, mob) || !player.canSee(mob)) continue;

            String entityType = Registries.ENTITY_TYPE.getId(mob.getType()).toString();
            double distance = player.distanceTo(mob);
            float currentHealth = mob.getHealth();
            float maxHealth = mob.getMaxHealth();
            int armor = mob.getArmor();
            double toughness = mob.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS);
            boolean isUndead = mob.getType().isIn(EntityTypeTags.UNDEAD);
            boolean isArthropod = mob.getType().isIn(EntityTypeTags.ARTHROPOD);

            int fuseTicksRemaining = -1;
            boolean isIgnited = false;
            int attackTicks = 20;

            if (mob instanceof CreeperEntity creeper) {
                fuseTicksRemaining = creeper.getFuseSpeed() > 0
                        ? (int) Math.ceil((1.0f - creeper.getClientFuseTime(0.0f)) * 30)
                        : 30;
                isIgnited = creeper.isIgnited();
                attackTicks = Math.max(1, (int) (distance / 0.25));
            } else if (mob instanceof AbstractSkeletonEntity) {
                attackTicks = 20;
            } else if (mob instanceof ZombieEntity) {
                attackTicks = Math.max(1, (int) (distance / 0.23));
            }

            // Check if mob is near another teammate or shelter/bed/workstation
            boolean nearTeammateOrShelter = false;
            for (ServerPlayerEntity otherPlayer : world.getPlayers()) {
                if (otherPlayer != player && otherPlayer.distanceTo(mob) <= 5.0) {
                    nearTeammateOrShelter = true;
                    break;
                }
            }

            mobObservations.add(new MobThreatObservation(
                    mob.getUuid(),
                    entityType,
                    distance,
                    currentHealth,
                    maxHealth,
                    armor,
                    toughness,
                    isUndead,
                    isArthropod,
                    true,
                    fuseTicksRemaining,
                    isIgnited,
                    attackTicks,
                    nearTeammateOrShelter
            ));
        }

        mobObservations.sort(Comparator.comparingDouble(MobThreatObservation::distance));

        // 4. Check for safe escape corridor
        boolean hasSafeEscapeCorridor = false;
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            boolean corridorClear = true;
            for (int step = 1; step <= 3; step++) {
                BlockPos targetPos = feetPos.offset(dir, step);
                BlockPos groundPos = targetPos.down();
                if (!world.isChunkLoaded(targetPos) || !world.isChunkLoaded(groundPos)) {
                    corridorClear = false;
                    break;
                }
                var support = world.getBlockState(groundPos);
                if (!support.isSideSolidFullSquare(world, groundPos, Direction.UP)) {
                    corridorClear = false;
                    break;
                }
                var targetState = world.getBlockState(targetPos);
                if (!world.getFluidState(targetPos).isEmpty() || (!targetState.isAir() && !targetState.isReplaceable())) {
                    corridorClear = false;
                    break;
                }
            }
            if (corridorClear) {
                hasSafeEscapeCorridor = true;
                break;
            }
        }

        // 5. Standing in hazard
        boolean isStandingInHazard = PlayerRuntime.isHazardousSituation(player);

        String heldItemId = Registries.ITEM.getId(player.getMainHandStack().getItem()).toString();

        return new TacticalObservation(
                companionId,
                serverTick,
                bodyGeneration,
                player.getServerWorld().getRegistryKey().getValue().toString(),
                player.getHealth(),
                player.getMaxHealth(),
                player.getHungerManager().getFoodLevel(),
                player.getInventory().selectedSlot,
                heldItemId,
                weapons,
                mobObservations,
                hasSafeEscapeCorridor,
                isStandingInHazard,
                foodTaskAuthorized,
                shelterProtectionAuthorized,
                canPerformCritical
        );
    }

    static List<WeaponProfile> scanWeaponProfiles(ServerPlayerEntity player) {
        List<WeaponProfile> weapons = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            boolean isSword = item instanceof SwordItem;
            boolean isAxe = item instanceof AxeItem;
            boolean isTool = isSword || isAxe || item instanceof MiningToolItem;

            double[] stats = new double[]{1.0, 4.0};
            stack.applyAttributeModifiers(EquipmentSlot.MAINHAND, (attr, modifier) -> {
                if (attr.equals(EntityAttributes.GENERIC_ATTACK_DAMAGE) || attr.getIdAsString().contains("attack_damage")) {
                    stats[0] += modifier.value();
                } else if (attr.equals(EntityAttributes.GENERIC_ATTACK_SPEED) || attr.getIdAsString().contains("attack_speed")) {
                    stats[1] += modifier.value();
                }
            });

            if (isTool || stats[0] > 1.0) {
                double baseDamage = Math.max(1.0, stats[0]);
                double attackSpeed = Math.max(0.1, stats[1]);
                int durability = stack.isDamageable() ? Math.max(0, stack.getMaxDamage() - stack.getDamage()) : 1000;
                long fingerprint = ((long) Registries.ITEM.getRawId(item) << 32) ^ stack.getDamage() ^ stack.getComponents().hashCode();

                Map<String, Integer> enchantments = new HashMap<>();
                ItemEnchantmentsComponent enchants = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
                for (var enchEntry : enchants.getEnchantmentEntries()) {
                    String id = enchEntry.getKey().getIdAsString();
                    if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
                    enchantments.put(id, enchEntry.getIntValue());
                }

                boolean crossbowCharged = item instanceof net.minecraft.item.CrossbowItem
                        && stack.contains(DataComponentTypes.CHARGED_PROJECTILES);

                weapons.add(new WeaponProfile(
                        slot,
                        Registries.ITEM.getId(item).toString(),
                        fingerprint,
                        baseDamage,
                        attackSpeed,
                        durability,
                        enchantments,
                        isAxe,
                        isSword,
                        crossbowCharged
                ));
            }
        }
        return weapons;
    }

    private static boolean loadedSightline(ServerPlayerEntity body, LivingEntity other) {
        for (int x = Math.min(body.getBlockX(), other.getBlockX()) >> 4;
             x <= Math.max(body.getBlockX(), other.getBlockX()) >> 4; x++) {
            for (int z = Math.min(body.getBlockZ(), other.getBlockZ()) >> 4;
                 z <= Math.max(body.getBlockZ(), other.getBlockZ()) >> 4; z++) {
                if (!body.getServerWorld().isChunkLoaded(new BlockPos(x << 4, body.getBlockY(), z << 4))) return false;
            }
        }
        return true;
    }
}
