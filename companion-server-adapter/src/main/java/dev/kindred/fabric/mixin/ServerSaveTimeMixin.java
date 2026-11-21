package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TickPauseLog;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In plain terms: notes when the server saves the world and for how long, so that a stall in the log can say whether
 * a save fell in it (F-22c101). It changes nothing about the save.
 */
@Mixin(MinecraftServer.class)
abstract class ServerSaveTimeMixin {
    @Inject(method = "saveAll", at = @At("HEAD"))
    private void kindred$saveStarts(boolean suppressLogs, boolean flush, boolean force, CallbackInfoReturnable<Boolean> info) {
        TickPauseLog.saveStarted();
    }

    @Inject(method = "saveAll", at = @At("RETURN"))
    private void kindred$saveEnds(boolean suppressLogs, boolean flush, boolean force, CallbackInfoReturnable<Boolean> info) {
        TickPauseLog.saveEnded();
    }
}
