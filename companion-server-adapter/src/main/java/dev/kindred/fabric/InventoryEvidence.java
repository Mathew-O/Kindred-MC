package dev.kindred.fabric;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Server-thread comparison snapshots only; workers receive just the fingerprint string. */
record InventoryEvidence(ItemStack[] stacks, String description) {
    static InventoryEvidence capture(NativePlayerBody body) {
        var inventory = body.entity().getInventory();
        var stacks = new ItemStack[inventory.size()];
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < stacks.length; i++) {
                var stack = inventory.getStack(i);
                stacks[i] = stack.copy();
                String data = i + ":" + Registries.ITEM.getId(stack.getItem()) + ":" + stack.getCount() + ":" + stack.getComponents() + "\n";
                digest.update(data.getBytes(StandardCharsets.UTF_8));
            }
            return new InventoryEvidence(stacks, "native owned inventory slots=" + stacks.length + "; sha256=" + HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    int itemTotal() {
        int total = 0;
        for (ItemStack stack : stacks) total += stack.getCount();
        return total;
    }

    /** Same items, counts and components regardless of slot, so an owned tool moved within the inventory still matches. */
    boolean sameContents(NativePlayerBody body) {
        var expected = new java.util.HashMap<String, Integer>();
        for (ItemStack stack : stacks) if (!stack.isEmpty()) expected.merge(key(stack), stack.getCount(), Integer::sum);
        var inventory = body.entity().getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            Integer left = expected.get(key(stack));
            if (left == null || left < stack.getCount()) return false;
            if (left == stack.getCount()) expected.remove(key(stack)); else expected.put(key(stack), left - stack.getCount());
        }
        return expected.isEmpty();
    }

    private static String key(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()) + "|" + stack.getComponents();
    }

    boolean matches(NativePlayerBody body) {
        var inventory = body.entity().getInventory();
        if (inventory.size() != stacks.length) return false;
        for (int i = 0; i < stacks.length; i++) {
            var actual = inventory.getStack(i);
            if (actual.getCount() != stacks[i].getCount() || !ItemStack.areItemsAndComponentsEqual(actual, stacks[i])) return false;
        }
        return true;
    }
}
