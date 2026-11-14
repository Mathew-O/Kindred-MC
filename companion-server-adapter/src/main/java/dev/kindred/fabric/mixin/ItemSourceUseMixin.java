package dev.kindred.fabric.mixin;

import dev.kindred.fabric.EvalItemSources;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The item-source audit (M-06k, evaluation runs only): an item used, in the air or on a block (a bucket filled, a bottle). */
@Mixin(ServerPlayerInteractionManager.class)
abstract class ItemSourceUseMixin {
    @Inject(method = "interactItem", at = @At("HEAD"))
    private void kindred$itemSourceUseBegins(ServerPlayerEntity player, World world, ItemStack stack, Hand hand, CallbackInfoReturnable<ActionResult> info) {
        if (EvalItemSources.on) EvalItemSources.enter(player);
    }

    @Inject(method = "interactItem", at = @At("RETURN"))
    private void kindred$itemSourceUseEnds(ServerPlayerEntity player, World world, ItemStack stack, Hand hand, CallbackInfoReturnable<ActionResult> info) {
        if (EvalItemSources.on) EvalItemSources.exit(player, "use:item");
    }

    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void kindred$itemSourceUseOnBlockBegins(ServerPlayerEntity player, World world, ItemStack stack, Hand hand, BlockHitResult hit,
                                                    CallbackInfoReturnable<ActionResult> info) {
        if (EvalItemSources.on) EvalItemSources.enter(player);
    }

    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void kindred$itemSourceUseOnBlockEnds(ServerPlayerEntity player, World world, ItemStack stack, Hand hand, BlockHitResult hit,
                                                  CallbackInfoReturnable<ActionResult> info) {
        if (EvalItemSources.on) EvalItemSources.exit(player, "use:block");
    }
}
