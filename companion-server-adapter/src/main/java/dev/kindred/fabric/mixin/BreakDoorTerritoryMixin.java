package dev.kindred.fabric.mixin;

import dev.kindred.fabric.TerritoryGuard;
import net.minecraft.entity.ai.goal.BreakDoorGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A zombie (or a vindicator) does not set about breaking a door on a faction's land or in a safe zone (M-06d1): the goal
 * never starts there, so the door is never broken nor its cracks shown.
 */
@Mixin(BreakDoorGoal.class)
abstract class BreakDoorTerritoryMixin {
    @Inject(method = "canStart", at = @At("RETURN"), cancellable = true)
    private void kindred$notOnProtectedLand(CallbackInfoReturnable<Boolean> info) {
        if (!info.getReturnValueZ()) return;
        var goal = (DoorInteractGoalAccessor) (Object) this;
        if (!TerritoryGuard.mayMobChange(goal.kindred$mob().getWorld(), goal.kindred$doorPos())) info.setReturnValue(false);
    }
}
