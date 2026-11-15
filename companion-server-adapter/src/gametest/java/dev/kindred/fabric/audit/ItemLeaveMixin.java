package dev.kindred.fabric.audit;

import dev.kindred.fabric.ItemLeaves;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every item entity leaving the world, passed to {@link ItemLeaves} (F-22c84's diagnostic, report only). */
@Mixin(Entity.class)
abstract class ItemLeaveMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void kindred$itemLeaves(Entity.RemovalReason reason, CallbackInfo info) {
        if ((Object) this instanceof ItemEntity item && !item.isRemoved()) ItemLeaves.leaving(item, reason);
    }
}
