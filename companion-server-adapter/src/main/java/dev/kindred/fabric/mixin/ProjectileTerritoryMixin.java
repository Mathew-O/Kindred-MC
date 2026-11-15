package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A projectile sets off nothing on another owner's land (M-06d): a button, a target, a bell, a chorus flower or
 * dripstone hit by an outsider's shot, or by a shot with no shooter, does not react. The projectile itself still lands.
 */
@Mixin(ProjectileEntity.class)
abstract class ProjectileTerritoryMixin {
    @Inject(method = "onBlockHit", at = @At("HEAD"), cancellable = true)
    private void kindred$noEffectOnProtectedLand(BlockHitResult hit, CallbackInfo info) {
        var self = (ProjectileEntity) (Object) this;
        if (TerritoryGuard.mayHit(self.getWorld(), self.getOwner(), hit.getBlockPos())) return;
        // A player's shot that would have changed the block is written for the land's faction (F-34c2); a plain arrow's miss
        // is cancelled the same but is no attempt on the land, and a shot with no shooter names no one.
        if (self.getOwner() instanceof net.minecraft.server.network.ServerPlayerEntity shooter && TerritoryGuard.wouldChange(self)) {
            TerritoryGuard.refused(shooter, self.getWorld(), hit.getBlockPos(), "hit");
        }
        info.cancel();
    }
}
