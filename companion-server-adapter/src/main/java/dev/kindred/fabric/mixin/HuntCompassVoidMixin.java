package dev.kindred.fabric.mixin;

import dev.kindred.fabric.HuntCompass;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A hunt's tracking compass fallen out of the world (M-06k2). */
@Mixin(Entity.class)
abstract class HuntCompassVoidMixin {
    @Inject(method = "tickInVoid", at = @At("HEAD"))
    private void kindred$huntCompassInTheVoid(CallbackInfo info) {
        if ((Object) this instanceof ItemEntity item) HuntCompass.destroyed(item.getStack());
    }
}
