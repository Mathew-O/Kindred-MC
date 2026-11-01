package dev.kindred.fabric.mixin;

import net.minecraft.entity.projectile.FishingBobberEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * In plain terms: a small opening into the game that lets an AI player tell when a fish has bitten, the
 * moment a human sees the float dip and reels in.
 *
 * Technical: Whether a fish is on the hook: a player sees it as the bobber's dip, the moment to reel in
 * (E-05).
 */
@Mixin(FishingBobberEntity.class)
public interface FishingBobberAccessor {
    @Accessor("hookCountdown") int kindred$hookCountdown();
}
