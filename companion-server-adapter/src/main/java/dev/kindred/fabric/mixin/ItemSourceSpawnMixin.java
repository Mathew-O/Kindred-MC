package dev.kindred.fabric.mixin;

import dev.kindred.fabric.EvalItemSources;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The item-source audit (M-06k, evaluation runs only): a drop is remembered by its entity with where it came from. */
@Mixin(ServerWorld.class)
abstract class ItemSourceSpawnMixin {
    @Inject(method = "spawnEntity", at = @At("HEAD"))
    private void kindred$itemSourceSpawned(Entity entity, CallbackInfoReturnable<Boolean> info) {
        if (EvalItemSources.on) EvalItemSources.spawned(entity);
    }
}
