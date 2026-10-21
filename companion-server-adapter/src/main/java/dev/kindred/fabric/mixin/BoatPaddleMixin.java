package dev.kindred.fabric.mixin;

import dev.kindred.fabric.PreparedNativePlayer;
import net.minecraft.entity.vehicle.BoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The paddles answer a native AI player's inputs (forward, left, right) where a client's would, at the same point in
 * the boat's tick: after drag and buoyancy, before the move. The inputs are the ordinary ones a player's client sets.
 */
@Mixin(BoatEntity.class)
public abstract class BoatPaddleMixin {
    @Shadow
    private void updatePaddles() {
        throw new AssertionError();
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/vehicle/BoatEntity;updateVelocity()V", shift = At.Shift.AFTER))
    private void kindred$nativeRowerPaddles(CallbackInfo info) {
        BoatEntity boat = (BoatEntity) (Object) this;
        if (!boat.getWorld().isClient && boat.getControllingPassenger() instanceof PreparedNativePlayer) updatePaddles();
    }
}
