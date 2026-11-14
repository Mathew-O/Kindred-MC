package dev.kindred.fabric.mixin;

import dev.kindred.fabric.EvalItemSources;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The item-source audit (M-06k, evaluation runs only): what a player picks up of a drop is credited to where the drop
 * came from, read as the pickup begins (a drop picked up whole is forgotten during it); a command's drop pays nothing.
 */
@Mixin(ItemEntity.class)
abstract class ItemSourcePickupMixin {
    @Unique
    private String kindred$pickupSource;

    @Inject(method = "onPlayerCollision", at = @At("HEAD"))
    private void kindred$itemSourcePickupBegins(PlayerEntity player, CallbackInfo info) {
        if (!EvalItemSources.on) return;
        var self = (ItemEntity) (Object) this;
        if (self.cannotPickup()) {
            kindred$pickupSource = null;
            return;
        }
        kindred$pickupSource = EvalItemSources.pickupSource(self);
        EvalItemSources.enter(player);
    }

    @Inject(method = "onPlayerCollision", at = @At("RETURN"))
    private void kindred$itemSourcePickupEnds(PlayerEntity player, CallbackInfo info) {
        if (!EvalItemSources.on || kindred$pickupSource == null) return;
        EvalItemSources.exit(player, kindred$pickupSource);
        kindred$pickupSource = null;
    }
}
