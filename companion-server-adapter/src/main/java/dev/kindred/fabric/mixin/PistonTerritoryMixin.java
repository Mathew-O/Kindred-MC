package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.piston.PistonHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A piston moves or breaks nothing across a claim's border into another owner's land (M-06d): every block it would
 * move, where each would land, and every block it would break must be land the piston's own chunk may reach.
 */
@Mixin(PistonBlock.class)
abstract class PistonTerritoryMixin {
    @Redirect(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/piston/PistonHandler;calculatePush()Z"))
    private boolean kindred$pushOnlyWhereAllowed(PistonHandler handler, World world, BlockPos pos, Direction dir, boolean extend) {
        if (!handler.calculatePush()) return false;
        Direction motion = extend ? dir : dir.getOpposite();
        if (!TerritoryGuard.mayCross(world, pos, pos.offset(dir))) return false;
        for (BlockPos moved : handler.getMovedBlocks()) {
            if (!TerritoryGuard.mayCross(world, pos, moved) || !TerritoryGuard.mayCross(world, pos, moved.offset(motion))) return false;
        }
        for (BlockPos broken : handler.getBrokenBlocks()) if (!TerritoryGuard.mayCross(world, pos, broken)) return false;
        return true;
    }
}
