package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An explosion breaks no block on a faction's land or in a safe zone (M-06d): those blocks leave its list first. */
@Mixin(Explosion.class)
abstract class ExplosionTerritoryMixin {
    @Shadow @Final private World world;

    @Inject(method = "affectWorld", at = @At("HEAD"))
    private void kindred$spareProtectedLand(boolean particles, CallbackInfo info) {
        ((Explosion) (Object) this).getAffectedBlocks().removeIf(pos -> !TerritoryGuard.mayExplode(world, pos));
    }
}
