package dev.kindred.fabric;

import dev.kindred.core.FocusDirector;
import dev.kindred.core.Mannerisms;
import dev.kindred.core.Personality;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: where an older-style AI body looks: it turns its head smoothly towards whatever caught its
 * attention instead of snapping around.
 *
 * Technical: Turns a focus choice into one head movement. The head eases toward a held subject instead of
 * snapping.
 */
final class FocusBehavior {
    private final FocusDirector director = new FocusDirector();

    /** Moves the head a little this tick. */
    void tick(CompanionEntity body, ServerPlayerEntity owner, boolean moving, Entity notice,
              Personality personality, Set<Mannerisms.Quirk> quirks) {
        List<LivingEntity> nearby = new ArrayList<>();
        body.getWorld().collectEntitiesByType(TypeFilter.instanceOf(LivingEntity.class),
                body.getBoundingBox().expand(8), entity -> entity != body, nearby, 32);
        Entity threat = null;
        List<UUID> interesting = new ArrayList<>();
        for (LivingEntity entity : nearby) {
            if (!SocialPerception.visible(body, entity)) continue;
            if (entity instanceof HostileEntity && threat == null) threat = entity;
            else if (!(entity instanceof HostileEntity) && !entity.getUuid().equals(owner.getUuid()) && interesting.size() < 8) {
                interesting.add(entity.getUuid());
            }
        }
        var choice = director.choose(body.age, moving, owner.getUuid(),
                notice != null && notice.isAlive() ? notice.getUuid() : null,
                threat == null ? null : threat.getUuid(), interesting, personality, quirks);
        Entity subject = switch (choice.anchor()) {
            case OWNER -> owner;
            case THREAT -> threat;
            case OTHER -> ((ServerWorld) body.getWorld()).getEntity(choice.subject() == null ? owner.getUuid() : choice.subject());
            case PATH -> null;
        };
        if (subject == null || !subject.isAlive() || !SocialPerception.visible(body, subject)) lookAhead(body, owner);
        else body.getLookControl().lookAt(subject, choice.anchor() == FocusDirector.Anchor.THREAT ? 22 : 12, 12);
    }

    void clear() { director.clear(); }

    /** Where the body is going, at eye height, so path focus is the route rather than the ground. */
    private static void lookAhead(CompanionEntity body, ServerPlayerEntity owner) {
        Vec3d velocity = body.getVelocity().multiply(1, 0, 1);
        if (velocity.horizontalLengthSquared() < 0.002) {
            body.getLookControl().lookAt(owner, 12, 12);
            return;
        }
        Vec3d aim = body.getPos().add(velocity.normalize().multiply(3)).add(0, body.getStandingEyeHeight(), 0);
        body.getLookControl().lookAt(aim.x, aim.y, aim.z, 10, 8);
    }
}
