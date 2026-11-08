package dev.kindred.fabric.mixin;

import dev.kindred.fabric.PlayerSaves;
import java.io.File;
import java.util.Optional;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.PlayerSaveHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In plain terms: while a trade's save of a player is still being written in the background, Minecraft's own saving and
 * loading of that player wait their turn, so the file is never overwritten with an older copy or read half-way.
 *
 * Technical: Only while {@link PlayerSaves#pending} for the player: a vanilla save is snapshotted now and queued on the
 * same ordered writer instead of written at once, and a vanilla load waits for the queued write first. Otherwise
 * vanilla is untouched.
 */
@Mixin(PlayerSaveHandler.class)
abstract class PlayerSaveHandlerMixin {
    @Shadow @Final private File playerDataDir;

    @Inject(method = "savePlayerData", at = @At("HEAD"), cancellable = true)
    private void kindred$afterPendingSave(PlayerEntity player, CallbackInfo info) {
        if (!PlayerSaves.pending(player.getUuid())) return;
        PlayerSaves.enqueue(player.getUuid(), playerDataDir.toPath(), player.writeNbt(new NbtCompound()));
        info.cancel();
    }

    @Inject(method = "loadPlayerData(Lnet/minecraft/entity/player/PlayerEntity;)Ljava/util/Optional;", at = @At("HEAD"))
    private void kindred$readAfterPendingSave(PlayerEntity player, CallbackInfoReturnable<Optional<NbtCompound>> result) {
        if (PlayerSaves.pending(player.getUuid())) PlayerSaves.await(player.getUuid());
    }
}
