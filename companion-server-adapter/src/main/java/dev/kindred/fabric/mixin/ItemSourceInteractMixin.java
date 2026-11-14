package dev.kindred.fabric.mixin;

import dev.kindred.fabric.EvalItemSources;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The item-source audit (M-06k, evaluation runs only): an item used on a creature (a cow milked, a bowl of stew). */
@Mixin(PlayerEntity.class)
abstract class ItemSourceInteractMixin {
    @Inject(method = "interact", at = @At("HEAD"))
    private void kindred$itemSourceInteractBegins(Entity entity, Hand hand, CallbackInfoReturnable<ActionResult> info) {
        if (EvalItemSources.on) EvalItemSources.enter((PlayerEntity) (Object) this);
    }

    @Inject(method = "interact", at = @At("RETURN"))
    private void kindred$itemSourceInteractEnds(Entity entity, Hand hand, CallbackInfoReturnable<ActionResult> info) {
        if (EvalItemSources.on) EvalItemSources.exit((PlayerEntity) (Object) this, "use:entity");
    }
}
