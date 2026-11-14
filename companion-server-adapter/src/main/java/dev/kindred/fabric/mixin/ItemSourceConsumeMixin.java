package dev.kindred.fabric.mixin;

import dev.kindred.fabric.EvalItemSources;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The item-source audit (M-06k, evaluation runs only): what eating or drinking leaves (a bowl, a bottle, a bucket). */
@Mixin(LivingEntity.class)
abstract class ItemSourceConsumeMixin {
    @Inject(method = "consumeItem", at = @At("HEAD"))
    private void kindred$itemSourceConsumeBegins(CallbackInfo info) {
        if (EvalItemSources.on) EvalItemSources.enter((LivingEntity) (Object) this);
    }

    @Inject(method = "consumeItem", at = @At("RETURN"))
    private void kindred$itemSourceConsumeEnds(CallbackInfo info) {
        if (EvalItemSources.on) EvalItemSources.exit((LivingEntity) (Object) this, "use:finish");
    }
}
