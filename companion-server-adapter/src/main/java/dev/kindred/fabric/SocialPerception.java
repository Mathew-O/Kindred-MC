package dev.kindred.fabric;

import dev.kindred.core.SocialProfile;
import dev.kindred.core.SocialSnapshot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What an AI player's body can see and hear of the people about it, for its social lane: the legacy companion body and
 * the native player's alike. {@code aiPlayer} names the AI player a body belongs to, so that another AI player is a
 * companion contact and not a person, whatever its body.
 */
final class SocialPerception {
    static final double RANGE_SQUARED = 8 * 8;

    private SocialPerception() { }

    /** A companion body's own AI player, as the legacy lane knows them. */
    static UUID companionOf(Entity entity) {
        return entity instanceof CompanionEntity companion ? companion.companionId() : null;
    }

    static boolean visible(LivingEntity body, Entity other) {
        return other != body && other.isAlive() && !other.isSpectator() && !other.isInvisible()
                && body.getWorld() == other.getWorld() && body.squaredDistanceTo(other) <= RANGE_SQUARED
                && loadedSightline(body, other) && body.canSee(other);
    }

    private static boolean loadedSightline(LivingEntity body, Entity other) {
        // Even a short diagonal ray can cross a third chunk. Do not let raycasting load it.
        for (int x = Math.min(body.getBlockX(), other.getBlockX()) >> 4;
             x <= Math.max(body.getBlockX(), other.getBlockX()) >> 4; x++) {
            for (int z = Math.min(body.getBlockZ(), other.getBlockZ()) >> 4;
                 z <= Math.max(body.getBlockZ(), other.getBlockZ()) >> 4; z++) {
                if (!body.getWorld().isChunkLoaded(new BlockPos(x << 4, body.getBlockY(), z << 4))) return false;
            }
        }
        return true;
    }

    static List<ServerPlayerEntity> listeners(CompanionEntity body, SocialProfile profile) {
        return listeners(body, body.ownerId(), profile, SocialPerception::companionOf);
    }

    /** The people who can hear it: its owner, and others only when it talks in public; never another AI player's body. */
    static List<ServerPlayerEntity> listeners(LivingEntity body, UUID owner, SocialProfile profile, Function<Entity, UUID> aiPlayer) {
        return listeners(body, ownedBy(owner), profile, aiPlayer);
    }

    /** As above, its owners being whoever {@code owns} admits: for a native player, whoever may command it (F-18a). */
    static List<ServerPlayerEntity> listeners(LivingEntity body, Predicate<ServerPlayerEntity> owns, SocialProfile profile, Function<Entity, UUID> aiPlayer) {
        return ((ServerWorld) body.getWorld()).getPlayers(player -> aiPlayer.apply(player) == null && visible(body, player)
                && (profile.publicConversation() || owns.test(player)), 16);
    }

    /** The one person whose id is {@code owner}. */
    static Predicate<ServerPlayerEntity> ownedBy(UUID owner) {
        return player -> player.getUuid().equals(owner);
    }

    static List<SocialSnapshot.Contact> noticeable(CompanionEntity body) {
        return noticeable(body, body.ownerId(), SocialPerception::companionOf);
    }

    /**
     * Visible people for a physical glance. Noticing someone is not conversation, so this list ignores the
     * public-conversation setting; the social brain still applies privacy before any remark.
     */
    static List<SocialSnapshot.Contact> noticeable(LivingEntity body, UUID owner, Function<Entity, UUID> aiPlayer) {
        return noticeable(body, ownedBy(owner), aiPlayer);
    }

    /** As above, its owners being whoever {@code owns} admits (F-18a). */
    static List<SocialSnapshot.Contact> noticeable(LivingEntity body, Predicate<ServerPlayerEntity> owns, Function<Entity, UUID> aiPlayer) {
        List<LivingEntity> nearby = new ArrayList<>();
        body.getWorld().collectEntitiesByType(TypeFilter.instanceOf(LivingEntity.class),
                body.getBoundingBox().expand(8), entity -> entity != body, nearby, 32);
        List<SocialSnapshot.Contact> contacts = new ArrayList<>();
        for (LivingEntity entity : nearby) {
            if (contacts.size() >= 16 || !visible(body, entity)) continue;
            UUID peer = aiPlayer.apply(entity);
            if (peer != null) {
                contacts.add(new SocialSnapshot.Contact(peer, entity.getName().getString(), SocialSnapshot.Kind.COMPANION));
            } else if (entity instanceof ServerPlayerEntity player) {
                boolean isOwner = owns.test(player);
                contacts.add(new SocialSnapshot.Contact(player.getUuid(), player.getName().getString(),
                        isOwner ? SocialSnapshot.Kind.OWNER : SocialSnapshot.Kind.PLAYER));
            }
        }
        return List.copyOf(contacts);
    }

    static SocialSnapshot observe(CompanionEntity body, SocialProfile profile, boolean active,
                                  Predicate<CompanionEntity> socialCompanion) {
        return observe(body, body.ownerId(), profile, active, SocialPerception::companionOf,
                entity -> entity instanceof CompanionEntity companion && socialCompanion.test(companion));
    }

    static SocialSnapshot observe(LivingEntity body, UUID owner, SocialProfile profile, boolean active,
                                  Function<Entity, UUID> aiPlayer, Predicate<Entity> socialCompanion) {
        return observe(body, ownedBy(owner), profile, active, aiPlayer, socialCompanion);
    }

    /** As above, its owners being whoever {@code owns} admits (F-18a). */
    static SocialSnapshot observe(LivingEntity body, Predicate<ServerPlayerEntity> owns, SocialProfile profile, boolean active,
                                  Function<Entity, UUID> aiPlayer, Predicate<Entity> socialCompanion) {
        List<LivingEntity> nearby = new ArrayList<>();
        // Cap candidates BEFORE visibility raycasts. This never scans remote/unloaded regions.
        body.getWorld().collectEntitiesByType(TypeFilter.instanceOf(LivingEntity.class),
                body.getBoundingBox().expand(8), entity -> entity != body, nearby, 32);
        List<SocialSnapshot.Contact> contacts = new ArrayList<>();
        String threat = null;
        for (LivingEntity entity : nearby) {
            if (!visible(body, entity)) continue;
            if (entity instanceof HostileEntity && threat == null) threat = entity.getType().getName().getString();
            if (contacts.size() >= 16) continue;
            UUID peer = aiPlayer.apply(entity);
            if (peer != null) {
                if (profile.publicConversation() && socialCompanion.test(entity)) {
                    contacts.add(new SocialSnapshot.Contact(peer, entity.getName().getString(), SocialSnapshot.Kind.COMPANION));
                }
            } else if (entity instanceof ServerPlayerEntity player) {
                boolean isOwner = owns.test(player);
                if (isOwner || profile.publicConversation()) contacts.add(new SocialSnapshot.Contact(player.getUuid(),
                        player.getName().getString(), isOwner ? SocialSnapshot.Kind.OWNER : SocialSnapshot.Kind.PLAYER));
            }
        }
        var world = body.getWorld();
        boolean sky = world.getDimension().hasSkyLight() && world.isSkyVisible(body.getBlockPos().up());
        boolean hurt = body.hurtTime > 0;
        return new SocialSnapshot(active, world.hasRain(body.getBlockPos()), sky && world.isNight(),
                hurt, threat, contacts, hurt ? harm(body.getRecentDamageSource(), aiPlayer) : null);
    }

    /**
     * What hurt it, from the game's own record of the damage: a player who struck it or whose arrow did, by name; a mob,
     * by kind; or the world. Null when the game kept no record. Another AI player that struck it is a player too.
     */
    static SocialSnapshot.Harm harm(DamageSource source, Function<Entity, UUID> aiPlayer) {
        if (source == null) return null;
        Entity by = source.getAttacker();
        if (by instanceof ServerPlayerEntity player) return new SocialSnapshot.Harm(SocialSnapshot.Harm.Source.PLAYER, player.getName().getString());
        if (by != null && aiPlayer.apply(by) != null) return new SocialSnapshot.Harm(SocialSnapshot.Harm.Source.PLAYER, by.getName().getString());
        if (by instanceof LivingEntity mob) return new SocialSnapshot.Harm(SocialSnapshot.Harm.Source.MOB, mob.getType().getName().getString());
        if (by != null) return null;
        String name = switch (source.getName()) {
            case "fall" -> "a fall";
            case "inFire", "onFire", "campfire" -> "fire";
            case "lava" -> "lava";
            case "drown" -> "no air";
            case "cactus", "sweetBerryBush" -> "thorns";
            case "hotFloor" -> "a magma block";
            case "starve" -> "hunger";
            case "freeze" -> "the cold";
            case "inWall" -> "being stuck in a wall";
            case "lightningBolt" -> "lightning";
            case "flyIntoWall" -> "a wall";
            default -> null;
        };
        return name == null ? null : new SocialSnapshot.Harm(SocialSnapshot.Harm.Source.WORLD, name);
    }
}
