package dev.kindred.fabric.mixin;

import dev.kindred.fabric.ArenaSnapshots;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A builder in creative for a battle throws nothing out (M-06k3a): what creative makes never lies on the ground. */
@Mixin(PlayerEntity.class)
abstract class ArenaThrowMixin {
    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void kindred$builderThrowsNothing(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> info) {
        if (ArenaSnapshots.building(((PlayerEntity) (Object) this).getUuid())) info.setReturnValue(null);
    }
}
