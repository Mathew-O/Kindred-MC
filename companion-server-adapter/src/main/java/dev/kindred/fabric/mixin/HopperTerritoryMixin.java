package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A hopper neither pulls from nor pushes into a container across a claim's border (M-06d), a double chest's other half
 * included: the half standing on another owner's land is out of its reach. Checked where a hopper's pull and push
 * begin, ahead of Fabric's own transfer lookup, so neither vanilla's inventories nor a mod's storage get round it.
 */
@Mixin(value = HopperBlockEntity.class, priority = 500)
abstract class HopperTerritoryMixin {
    @Inject(method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z", at = @At("HEAD"), cancellable = true)
    private static void kindred$pullOnlyWhereAllowed(World world, Hopper hopper, CallbackInfoReturnable<Boolean> info) {
        BlockPos at = BlockPos.ofFloored(hopper.getHopperX(), hopper.getHopperY(), hopper.getHopperZ());
        if (!TerritoryGuard.mayReachContainer(world, at, BlockPos.ofFloored(hopper.getHopperX(), hopper.getHopperY() + 1.0, hopper.getHopperZ()))) {
            info.setReturnValue(false);
        }
    }

    @Inject(method = "insert", at = @At("HEAD"), cancellable = true)
    private static void kindred$pushOnlyWhereAllowed(World world, BlockPos pos, HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> info) {
        var facing = hopper.getCachedState().get(net.minecraft.block.HopperBlock.FACING);
        if (!TerritoryGuard.mayReachContainer(world, pos, pos.offset(facing))) info.setReturnValue(false);
    }
}
