package dev.kindred.fabric;

import dev.kindred.core.SocialProfile;
import dev.kindred.core.SocialSnapshot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class SocialPerception {
    static final double RANGE_SQUARED = 8 * 8;

    private SocialPerception() { }

    static boolean visible(CompanionEntity body, Entity other) {
        return other != body && other.isAlive() && !other.isSpectator() && !other.isInvisible()
                && body.getWorld() == other.getWorld() && body.squaredDistanceTo(other) <= RANGE_SQUARED
                && loadedSightline(body, other) && body.canSee(other);
    }

    private static boolean loadedSightline(CompanionEntity body, Entity other) {
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
        return ((ServerWorld) body.getWorld()).getPlayers(player -> visible(body, player)
                && (profile.publicConversation() || player.getUuid().equals(body.ownerId())), 16);
    }

    /**
     * Visible people for a physical glance. Noticing someone is not conversation, so this list ignores the
     * public-conversation setting; the social brain still applies privacy before any remark.
     */
    static List<SocialSnapshot.Contact> noticeable(CompanionEntity body) {
        List<LivingEntity> nearby = new ArrayList<>();
        body.getWorld().collectEntitiesByType(TypeFilter.instanceOf(LivingEntity.class),
                body.getBoundingBox().expand(8), entity -> entity != body, nearby, 32);
        List<SocialSnapshot.Contact> contacts = new ArrayList<>();
        for (LivingEntity entity : nearby) {
            if (contacts.size() >= 16 || !visible(body, entity)) continue;
            if (entity instanceof ServerPlayerEntity player) {
                boolean owner = player.getUuid().equals(body.ownerId());
                contacts.add(new SocialSnapshot.Contact(player.getUuid(), player.getName().getString(),
                        owner ? SocialSnapshot.Kind.OWNER : SocialSnapshot.Kind.PLAYER));
            } else if (entity instanceof CompanionEntity companion && companion.companionId() != null) {
                contacts.add(new SocialSnapshot.Contact(companion.companionId(), companion.getName().getString(),
                        SocialSnapshot.Kind.COMPANION));
            }
        }
        return List.copyOf(contacts);
    }

    static SocialSnapshot observe(CompanionEntity body, SocialProfile profile, boolean active,
                                  Predicate<CompanionEntity> socialCompanion) {
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
            if (entity instanceof ServerPlayerEntity player) {
                boolean owner = player.getUuid().equals(body.ownerId());
                if (owner || profile.publicConversation()) contacts.add(new SocialSnapshot.Contact(player.getUuid(),
                        player.getName().getString(), owner ? SocialSnapshot.Kind.OWNER : SocialSnapshot.Kind.PLAYER));
            } else if (entity instanceof CompanionEntity companion && profile.publicConversation()
                    && socialCompanion.test(companion)) {
                contacts.add(new SocialSnapshot.Contact(companion.companionId(), companion.getName().getString(),
                        SocialSnapshot.Kind.COMPANION));
            }
        }
        var world = body.getWorld();
        boolean sky = world.getDimension().hasSkyLight() && world.isSkyVisible(body.getBlockPos().up());
        return new SocialSnapshot(active, world.hasRain(body.getBlockPos()), sky && world.isNight(),
                body.hurtTime > 0, threat, contacts);
    }
}
