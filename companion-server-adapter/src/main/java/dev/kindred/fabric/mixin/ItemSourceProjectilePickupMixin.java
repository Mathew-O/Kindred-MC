package dev.kindred.fabric.mixin;

import dev.kindred.fabric.EvalItemSources;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The item-source audit (M-06k, evaluation runs only): an arrow or trident picked up off the ground. */
@Mixin(PersistentProjectileEntity.class)
abstract class ItemSourceProjectilePickupMixin {
    @Inject(method = "onPlayerCollision", at = @At("HEAD"))
    private void kindred$itemSourceProjectileBegins(PlayerEntity player, CallbackInfo info) {
        if (EvalItemSources.on) EvalItemSources.enter(player);
    }

    @Inject(method = "onPlayerCollision", at = @At("RETURN"))
    private void kindred$itemSourceProjectileEnds(PlayerEntity player, CallbackInfo info) {
        if (EvalItemSources.on) EvalItemSources.exit(player, "pickup:projectile");
    }
}
