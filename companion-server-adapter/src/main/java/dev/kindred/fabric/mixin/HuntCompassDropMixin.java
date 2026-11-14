package dev.kindred.fabric.mixin;

import dev.kindred.fabric.HuntCompass;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A hunt's tracking compass lying on the ground and destroyed there (M-06k2): burnt, pricked or blown up (its damage
 * discards it) or despawned (its tick discards it), its stack still whole. A pickup, by a player, a hopper or a mob,
 * moves it and marks nothing.
 */
@Mixin(ItemEntity.class)
abstract class HuntCompassDropMixin {
    @Inject(method = "damage", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/ItemEntity;discard()V"))
    private void kindred$huntCompassDestroyed(net.minecraft.entity.damage.DamageSource source, float amount, CallbackInfoReturnable<Boolean> info) {
        HuntCompass.destroyed(((ItemEntity) (Object) this).getStack());
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/ItemEntity;discard()V"))
    private void kindred$huntCompassDespawned(CallbackInfo info) {
        HuntCompass.destroyed(((ItemEntity) (Object) this).getStack());
    }
}
