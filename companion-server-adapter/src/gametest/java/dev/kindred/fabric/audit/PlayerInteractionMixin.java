package dev.kindred.fabric.audit;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.kindred.fabric.FixtureBounds;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * A player's own placing and breaking, on the server's tick, marked as that player's for {@link FixtureBounds}
 * (F-22c75b): what a test's player sets outside the test's plot lies there for the tests beside it as much as what
 * its fixture lays (house2's player set a crafting table down in the plot next door, #274).
 */
@Mixin(ServerPlayerInteractionManager.class)
abstract class PlayerInteractionMixin {
    @Shadow @Final protected ServerPlayerEntity player;

    @WrapMethod(method = "interactBlock")
    private ActionResult kindred$placing(ServerPlayerEntity by, World world, ItemStack stack, Hand hand, BlockHitResult hit,
                                         Operation<ActionResult> original) {
        var outer = FixtureBounds.acting(by);
        try {
            return original.call(by, world, stack, hand, hit);
        } finally {
            FixtureBounds.acting(outer);
        }
    }

    @WrapMethod(method = "tryBreakBlock")
    private boolean kindred$breaking(BlockPos pos, Operation<Boolean> original) {
        var outer = FixtureBounds.acting(player);
        try {
            return original.call(pos);
        } finally {
            FixtureBounds.acting(outer);
        }
    }
}
