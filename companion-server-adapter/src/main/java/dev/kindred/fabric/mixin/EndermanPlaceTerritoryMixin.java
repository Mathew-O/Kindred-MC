package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An enderman sets no block down on a faction's land or in a safe zone (M-06d1); it keeps carrying it. */
@Mixin(targets = "net.minecraft.entity.mob.EndermanEntity$PlaceBlockGoal")
abstract class EndermanPlaceTerritoryMixin {
    @Inject(method = "canPlaceOn", at = @At("HEAD"), cancellable = true)
    private void kindred$notOnProtectedLand(World world, BlockPos posAbove, BlockState carried, BlockState stateAbove, BlockState state,
                                            BlockPos pos, CallbackInfoReturnable<Boolean> info) {
        if (!TerritoryGuard.mayMobChange(world, posAbove)) info.setReturnValue(false);
    }
}
