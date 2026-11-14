package dev.kindred.fabric.mixin;

import dev.kindred.fabric.HuntCompass;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A hunt's tracking compass deleted by the curse of vanishing at its holder's death (M-06k2): vanilla calls this only
 * when keepInventory is off, so with it on the compass is still held and nothing is marked.
 */
@Mixin(PlayerEntity.class)
abstract class HuntCompassVanishMixin {
    @Inject(method = "vanishCursedItems", at = @At("HEAD"))
    private void kindred$huntCompassVanishes(CallbackInfo info) {
        var inventory = ((PlayerEntity) (Object) this).getInventory();
        for (int i = 0; i < inventory.size(); i++) HuntCompass.destroyed(inventory.getStack(i));
    }
}
