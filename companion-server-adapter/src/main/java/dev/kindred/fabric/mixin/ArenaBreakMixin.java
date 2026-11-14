package dev.kindred.fabric.mixin;

import dev.kindred.fabric.ArenaSnapshots;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A build battle's arena keeps its blocks until it is put back (M-06k3a): no one but a builder building in creative may
 * break a block in it, so nothing there drops for anyone, during the battle or between the podium and the restore.
 */
@Mixin(ServerPlayerInteractionManager.class)
abstract class ArenaBreakMixin {
    @Shadow @Final protected ServerPlayerEntity player;
    @Shadow protected ServerWorld world;

    @Inject(method = "tryBreakBlock", at = @At("HEAD"), cancellable = true)
    private void kindred$arenaKeepsItsBlocks(BlockPos pos, CallbackInfoReturnable<Boolean> info) {
        if (!ArenaSnapshots.closed(world, pos) || ArenaSnapshots.building(player.getUuid())) return;
        player.networkHandler.sendPacket(new BlockUpdateS2CPacket(world, pos));
        info.setReturnValue(false);
    }
}
