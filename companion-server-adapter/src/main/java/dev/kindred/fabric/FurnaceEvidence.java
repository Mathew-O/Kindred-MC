package dev.kindred.fabric;

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

/**
 * Server-thread snapshot of furnace slots (input, fuel, output) for item conservation and action ledger evidence.
 */
record FurnaceEvidence(ItemStack input, ItemStack fuel, ItemStack output, String description) {
    static FurnaceEvidence capture(AbstractFurnaceBlockEntity furnace) {
        ItemStack in = furnace.getStack(0).copy();
        ItemStack fl = furnace.getStack(1).copy();
        ItemStack out = furnace.getStack(2).copy();
        String desc = "furnace[in=" + (in.isEmpty() ? "empty" : (Registries.ITEM.getId(in.getItem()) + "x" + in.getCount()))
                + "; fuel=" + (fl.isEmpty() ? "empty" : (Registries.ITEM.getId(fl.getItem()) + "x" + fl.getCount()))
                + "; out=" + (out.isEmpty() ? "empty" : (Registries.ITEM.getId(out.getItem()) + "x" + out.getCount())) + "]";
        return new FurnaceEvidence(in, fl, out, desc);
    }

    boolean matches(AbstractFurnaceBlockEntity furnace) {
        return ItemStack.areItemsAndComponentsEqual(input, furnace.getStack(0))
                && input.getCount() == furnace.getStack(0).getCount()
                && ItemStack.areItemsAndComponentsEqual(fuel, furnace.getStack(1))
                && fuel.getCount() == furnace.getStack(1).getCount()
                && ItemStack.areItemsAndComponentsEqual(output, furnace.getStack(2))
                && output.getCount() == furnace.getStack(2).getCount();
    }
}
