package dev.kindred.fabric.mixin;

import dev.kindred.fabric.PreparedNativePlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.BoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A boat rowed by a native AI player moves on the server. Vanilla moves a player's boat only on that player's own
 * client, which a native player does not have, so its boat would sit still whatever it pressed.
 */
@Mixin(Entity.class)
public abstract class BoatAuthorityMixin {
    @Inject(method = "isLogicalSideForUpdatingMovement", at = @At("HEAD"), cancellable = true)
    private void kindred$nativeRowerMovesTheBoat(CallbackInfoReturnable<Boolean> result) {
        if ((Object) this instanceof BoatEntity boat && !boat.getWorld().isClient
                && boat.getControllingPassenger() instanceof PreparedNativePlayer) result.setReturnValue(true);
    }
}
