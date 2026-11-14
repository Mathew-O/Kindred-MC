package dev.kindred.fabric.mixin;

import dev.kindred.fabric.EvalItemSources;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The item-source audit (M-06k, evaluation runs only): a drop destroyed is forgotten; one unloaded with its chunk is not. */
@Mixin(Entity.class)
abstract class ItemSourceRemoveMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void kindred$itemSourceRemoved(Entity.RemovalReason reason, CallbackInfo info) {
        if (EvalItemSources.on) EvalItemSources.removed((Entity) (Object) this, reason);
    }
}
