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

/**
 * An explosion breaks no block on a faction's land or in a safe zone (M-06d): those blocks leave its list first. One a
 * player set off is written for the land's faction, once a chunk (F-34c2).
 */
@Mixin(Explosion.class)
abstract class ExplosionTerritoryMixin {
    @Shadow @Final private World world;

    @Inject(method = "affectWorld", at = @At("HEAD"))
    private void kindred$spareProtectedLand(boolean particles, CallbackInfo info) {
        var explosion = (Explosion) (Object) this;
        // Set off by a player (TNT that player lit: its causing entity), each chunk it was kept from is written once for the
        // land's faction (F-34c2); a blast with no player behind it (a creeper's, a lone TNT's) names no one and writes nothing.
        var igniter = explosion.getCausingEntity() instanceof net.minecraft.server.network.ServerPlayerEntity player ? player : null;
        var told = new java.util.HashSet<Long>();
        explosion.getAffectedBlocks().removeIf(pos -> {
            if (TerritoryGuard.mayExplode(world, pos)) return false;
            if (igniter != null && told.add(net.minecraft.util.math.ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4))) TerritoryGuard.refused(igniter, world, pos, "explode");
            return true;
        });
    }
}
