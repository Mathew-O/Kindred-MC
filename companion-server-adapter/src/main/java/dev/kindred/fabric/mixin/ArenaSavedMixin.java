package dev.kindred.fabric.mixin;

import dev.kindred.fabric.ArenaSnapshots;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** After a whole save of the server, a restored arena's snapshot may be forgotten: its blocks are on disk (M-06k3a). */
@Mixin(MinecraftServer.class)
abstract class ArenaSavedMixin {
    @Inject(method = "saveAll", at = @At("TAIL"))
    private void kindred$arenasSaved(boolean suppressLogs, boolean flush, boolean force, CallbackInfoReturnable<Boolean> info) {
        ArenaSnapshots.worldSaved();
    }
}
