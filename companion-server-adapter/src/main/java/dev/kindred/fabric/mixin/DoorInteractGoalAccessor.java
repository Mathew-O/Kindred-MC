package dev.kindred.fabric.mixin;

import net.minecraft.entity.ai.goal.DoorInteractGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The door a door goal is at, and its mob (M-06d1). */
@Mixin(DoorInteractGoal.class)
public interface DoorInteractGoalAccessor {
    @Accessor("doorPos")
    BlockPos kindred$doorPos();

    @Accessor("mob")
    MobEntity kindred$mob();
}
