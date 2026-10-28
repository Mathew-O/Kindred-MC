package dev.kindred.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import dev.kindred.fabric.PreparedNativePlayer;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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

    /** Vanilla respawn builds a fresh entity; a native body's replacement must stay a native body. */
    @WrapOperation(method = "respawnPlayer", at = @At(value = "NEW", target = "net/minecraft/server/network/ServerPlayerEntity"))
    private ServerPlayerEntity kindred$nativeRespawn(MinecraftServer server, ServerWorld world, GameProfile profile,
                                                     SyncedClientOptions options, Operation<ServerPlayerEntity> original,
                                                     ServerPlayerEntity player, boolean alive, Entity.RemovalReason reason) {
        if (player instanceof PreparedNativePlayer nativePlayer) return nativePlayer.kindred$respawned(world);
        return original.call(server, world, profile, options);
    }
}
