package dev.kindred.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.kindred.fabric.SleepCount;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.SleepManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * In plain terms: an AI player that is not in bed is not counted among the players a night waits for (E-70a), as a
 * spectator is not, so it never holds up a person's night. People are counted as vanilla counts them.
 */
@Mixin(SleepManager.class)
abstract class SleepCountMixin {
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;isSpectator()Z"))
    private boolean kindred$aiPlayerNotInBed(ServerPlayerEntity player, Operation<Boolean> original) {
        return original.call(player) || SleepCount.leftOut(player);
    }
}
