package dev.kindred.fabric.mixin;

import dev.kindred.fabric.PreparedNativePlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.BoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In plain terms: a small patch so a boat rowed, or a horse ridden, by an AI player actually moves. Normally
 * either is moved by its player's own copy of the game, which an AI player does not have.
 *
 * Technical: A boat rowed by a native AI player moves on the server. Vanilla moves a player's boat only on
 * that player's own client, which a native player does not have, so its boat would sit still whatever it
 * pressed. A horse (or donkey, mule, llama) it rides likewise (M-01a): ridden by a native player, it is moved on
 * the server from that player's own inputs, as a client moves it from its player's.
 */
@Mixin(Entity.class)
public abstract class BoatAuthorityMixin {
    @Inject(method = "isLogicalSideForUpdatingMovement", at = @At("HEAD"), cancellable = true)
    private void kindred$nativeRowerMovesTheBoat(CallbackInfoReturnable<Boolean> result) {
        if ((Object) this instanceof BoatEntity boat && !boat.getWorld().isClient
                && boat.getControllingPassenger() instanceof PreparedNativePlayer) result.setReturnValue(true);
        if ((Object) this instanceof net.minecraft.entity.passive.AbstractHorseEntity horse && !horse.getWorld().isClient
                && horse.getControllingPassenger() instanceof PreparedNativePlayer) result.setReturnValue(true);
    }
}
