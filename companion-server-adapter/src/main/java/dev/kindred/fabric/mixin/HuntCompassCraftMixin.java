package dev.kindred.fabric.mixin;

import dev.kindred.fabric.HuntCompass;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.RecipeInputInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A hunt's tracking compass makes nothing (M-06k2): a grid holding one, at a crafting table or in a player's own grid,
 * shows no result, so the hunt's one item cannot become a map or a recovery compass that outlives the hunt.
 */
@Mixin(CraftingScreenHandler.class)
abstract class HuntCompassCraftMixin {
    @Inject(method = "updateResult", at = @At("HEAD"), cancellable = true)
    private static void kindred$huntCompassMakesNothing(ScreenHandler handler, World world, PlayerEntity player, RecipeInputInventory grid,
                                                        CraftingResultInventory result, @Nullable RecipeEntry<CraftingRecipe> recipe, CallbackInfo info) {
        for (int i = 0; i < grid.size(); i++) {
            if (!HuntCompass.handed(grid.getStack(i))) continue;
            result.setStack(0, ItemStack.EMPTY);
            handler.setPreviousTrackedSlot(0, ItemStack.EMPTY);
            if (player instanceof ServerPlayerEntity server) {
                server.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(handler.syncId, handler.nextRevision(), 0, ItemStack.EMPTY));
            }
            info.cancel();
            return;
        }
    }
}
