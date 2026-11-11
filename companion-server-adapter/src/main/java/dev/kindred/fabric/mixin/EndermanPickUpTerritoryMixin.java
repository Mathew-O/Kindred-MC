package dev.kindred.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An enderman takes no block from a faction's land or a safe zone (M-06d1): the block it looked at is not holdable there,
 * so it neither removes it nor carries one.
 */
@Mixin(targets = "net.minecraft.entity.mob.EndermanEntity$PickUpBlockGoal")
abstract class EndermanPickUpTerritoryMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/BlockState;isIn(Lnet/minecraft/registry/tag/TagKey;)Z"))
    private boolean kindred$notFromProtectedLand(boolean holdable, @Local World world, @Local BlockPos pos) {
        return holdable && TerritoryGuard.mayMobChange(world, pos);
    }
}
