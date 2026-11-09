package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water and lava do not flow across a claim's border into another owner's land (M-06d). */
@Mixin(FlowableFluid.class)
abstract class FluidTerritoryMixin {
    @Inject(method = "flow", at = @At("HEAD"), cancellable = true)
    private void kindred$stayOutOfProtectedLand(WorldAccess access, BlockPos pos, BlockState state, Direction direction, FluidState fluid, CallbackInfo info) {
        if (access instanceof World world && !TerritoryGuard.mayCross(world, pos.offset(direction.getOpposite()), pos)) info.cancel();
    }
}
