package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.DropperBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dropper drops nothing into another owner's land across a claim's border (M-06d); it overrides the dispenser's own. */
@Mixin(DropperBlock.class)
abstract class DropperTerritoryMixin {
    @Inject(method = "dispense", at = @At("HEAD"), cancellable = true)
    private void kindred$dropOnlyWhereAllowed(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo info) {
        if (!TerritoryGuard.mayCross(world, pos, pos.offset(state.get(DispenserBlock.FACING)))) info.cancel();
    }
}
