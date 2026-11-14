package dev.kindred.fabric.mixin;

import dev.kindred.fabric.EvalItemSources;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The item-source audit (M-06k, evaluation runs only): a click in a screen (a craft's result, a furnace's output, a
 * trade, a chest's slot), credited to the screen; Kindred's own market and auction screens hand out nothing themselves.
 */
@Mixin(ScreenHandler.class)
abstract class ItemSourceScreenMixin {
    @Inject(method = "onSlotClick", at = @At("HEAD"))
    private void kindred$itemSourceClickBegins(int slot, int button, SlotActionType action, PlayerEntity player, CallbackInfo info) {
        if (EvalItemSources.on) EvalItemSources.enter(player);
    }

    @Inject(method = "onSlotClick", at = @At("RETURN"))
    private void kindred$itemSourceClickEnds(int slot, int button, SlotActionType action, PlayerEntity player, CallbackInfo info) {
        if (EvalItemSources.on) EvalItemSources.exit(player, EvalItemSources.screenSource((ScreenHandler) (Object) this));
    }
}
