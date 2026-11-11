package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A wither tears through no block on a faction's land or in a safe zone (M-06d1): the block stays, nothing drops. Its
 * skulls' blasts are explosions, already spared by {@link ExplosionTerritoryMixin}.
 */
@Mixin(WitherEntity.class)
abstract class WitherTerritoryMixin {
    @Redirect(method = "mobTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;breakBlock(Lnet/minecraft/util/math/BlockPos;ZLnet/minecraft/entity/Entity;)Z"))
    private boolean kindred$notOnProtectedLand(World world, BlockPos pos, boolean drop, Entity breaker) {
        return TerritoryGuard.mayMobChange(world, pos) && world.breakBlock(pos, drop, breaker);
    }
}
