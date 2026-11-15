package dev.kindred.fabric.audit;

import dev.kindred.fabric.ItemLeaves;
import net.minecraft.block.BlockState;
import net.minecraft.block.LecternBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A lectern replaced by anything else, passed to {@link ItemLeaves} (F-22c84's diagnostic, report only). */
@Mixin(LecternBlock.class)
abstract class LecternGoneMixin {
    @Inject(method = "onStateReplaced", at = @At("HEAD"))
    private void kindred$lecternGone(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved, CallbackInfo info) {
        if (!newState.isOf(state.getBlock())) ItemLeaves.lecternGone(world, pos, newState);
    }
}
