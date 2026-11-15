package dev.kindred.fabric.audit;

import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An item's age and pickup delay, read for F-22c84's diagnostic. */
@Mixin(ItemEntity.class)
public interface ItemEntityAccess {
    @Accessor("itemAge") int kindred$age();
    @Accessor("pickupDelay") int kindred$pickupDelay();
}
