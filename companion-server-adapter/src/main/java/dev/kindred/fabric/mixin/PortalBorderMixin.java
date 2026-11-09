package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In a faction world a Nether portal that would lead beyond the border takes nobody anywhere, and says so (M-06f),
 * where vanilla would put the traveller at the border's edge instead.
 */
@Mixin(NetherPortalBlock.class)
abstract class PortalBorderMixin {
    @Inject(method = "createTeleportTarget", at = @At("HEAD"), cancellable = true)
    private void kindred$refuseBeyondTheBorder(ServerWorld world, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTarget> info) {
        if (TerritoryGuard.portalRefused(world, entity, pos)) info.setReturnValue(null);
    }
}
