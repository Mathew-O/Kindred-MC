package dev.kindred.fabric.mixin;

import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.SmithingTransformRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A smithing upgrade's parts, for the market's recipe list (M-06g); vanilla lists no ingredients for it. */
@Mixin(SmithingTransformRecipe.class)
public interface SmithingTransformAccessor {
    @Accessor("template") Ingredient kindred$template();
    @Accessor("base") Ingredient kindred$base();
    @Accessor("addition") Ingredient kindred$addition();
    @Accessor("result") ItemStack kindred$result();
}
