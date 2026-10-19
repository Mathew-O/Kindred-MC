package dev.kindred.fabric.mixin;

import dev.kindred.fabric.PreparedNativePlayer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;

@Mixin(PlayerManager.class)
abstract class PlayerManagerMixin {
    /** Human loading is untouched. Native bodies must never fall back to an unprepared disk read. */
    @Inject(method = "loadPlayerData", at = @At("HEAD"), cancellable = true)
    private void kindred$preparedState(ServerPlayerEntity player, CallbackInfoReturnable<Optional<NbtCompound>> result) {
        if (player instanceof PreparedNativePlayer nativePlayer) result.setReturnValue(nativePlayer.kindred$loadPreparedState());
    }
}
