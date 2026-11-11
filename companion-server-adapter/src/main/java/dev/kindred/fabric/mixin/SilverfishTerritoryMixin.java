package dev.kindred.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A silverfish hides in no block on a faction's land or in a safe zone (M-06d1): the stone there is not infestable to it. */
@Mixin(targets = "net.minecraft.entity.mob.SilverfishEntity$WanderAndInfestGoal")
abstract class SilverfishTerritoryMixin {
    @ModifyExpressionValue(method = "start", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/InfestedBlock;isInfestable(Lnet/minecraft/block/BlockState;)Z"))
    private boolean kindred$notInProtectedLand(boolean infestable, @Local WorldAccess world, @Local BlockPos pos) {
        return infestable && (!(world instanceof World w) || TerritoryGuard.mayMobChange(w, pos));
    }
}
