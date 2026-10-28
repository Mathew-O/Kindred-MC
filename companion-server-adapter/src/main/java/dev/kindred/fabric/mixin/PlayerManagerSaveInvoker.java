package dev.kindred.fabric.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * In plain terms: a small hook that lets the mod ask Minecraft to save one AI player's body (inventory,
 * health, position) the normal way, exactly as it saves a human player.
 *
 * Technical: Invoke the existing native physical-state writer for one owned body.
 */
@Mixin(PlayerManager.class)
public interface PlayerManagerSaveInvoker {
    @Invoker("savePlayerData") void kindred$saveNativePlayer(ServerPlayerEntity player);
}
