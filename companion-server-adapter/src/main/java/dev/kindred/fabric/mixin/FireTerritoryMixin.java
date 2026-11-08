package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.block.FireBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fire burns no block on claimed land or in a safe zone (M-06d), as explosions break none there. */
@Mixin(FireBlock.class)
abstract class FireTerritoryMixin {
    @Inject(method = "trySpreadingFire", at = @At("HEAD"), cancellable = true)
    private void kindred$noBurningProtectedLand(World world, BlockPos pos, int spreadFactor, Random random, int currentAge, CallbackInfo info) {
        if (!TerritoryGuard.mayExplode(world, pos)) info.cancel();
    }
}
