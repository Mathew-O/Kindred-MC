package dev.kindred.fabric.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invoke the existing native physical-state writer for one owned body. */
@Mixin(PlayerManager.class)
public interface PlayerManagerSaveInvoker {
    @Invoker("savePlayerData") void kindred$saveNativePlayer(ServerPlayerEntity player);
}
