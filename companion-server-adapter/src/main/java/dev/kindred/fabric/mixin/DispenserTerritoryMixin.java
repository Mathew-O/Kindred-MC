package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dispenser (and a dropper, its subclass, below) fires nothing into another owner's land across a claim's border (M-06d). */
@Mixin(DispenserBlock.class)
abstract class DispenserTerritoryMixin {
    @Inject(method = "dispense", at = @At("HEAD"), cancellable = true)
    private void kindred$fireOnlyWhereAllowed(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo info) {
        if (!TerritoryGuard.mayCross(world, pos, pos.offset(state.get(DispenserBlock.FACING)))) info.cancel();
    }
}
