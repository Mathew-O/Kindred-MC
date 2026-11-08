package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A fire charge or blaze fireball sets no fire on another owner's land (M-06d). */
@Mixin(SmallFireballEntity.class)
abstract class SmallFireballTerritoryMixin {
    @Inject(method = "onBlockHit", at = @At("HEAD"), cancellable = true)
    private void kindred$noFireOnProtectedLand(BlockHitResult hit, CallbackInfo info) {
        var self = (SmallFireballEntity) (Object) this;
        var at = hit.getBlockPos().offset(hit.getSide());
        if (!TerritoryGuard.mayHit(self.getWorld(), self.getOwner(), at) || !TerritoryGuard.mayHit(self.getWorld(), self.getOwner(), hit.getBlockPos())) info.cancel();
    }
}
