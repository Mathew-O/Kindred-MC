package dev.kindred.fabric;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Server-thread snapshot of a container inventory for conservation checks (CHEST-01). */
record ChestEvidence(ItemStack[] stacks, String description) {
    static ChestEvidence capture(Inventory inventory) {
        int size = inventory.size();
        var stacks = new ItemStack[size];
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < size; i++) {
                var stack = inventory.getStack(i);
                stacks[i] = stack.copy();
                String data = i + ":" + Registries.ITEM.getId(stack.getItem()) + ":" + stack.getCount() + ":" + stack.getComponents() + "\n";
                digest.update(data.getBytes(StandardCharsets.UTF_8));
            }
            return new ChestEvidence(stacks, "container slots=" + size + "; sha256=" + HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static ChestEvidence capture(BlockEntity entity) {
        if (!(entity instanceof Inventory inventory)) {
            return new ChestEvidence(new ItemStack[0], "not_a_container");
        }
        return capture(inventory);
    }

    boolean matches(Inventory inventory) {
        if (inventory.size() != stacks.length) return false;
        for (int i = 0; i < stacks.length; i++) {
            var actual = inventory.getStack(i);
            if (actual.getCount() != stacks[i].getCount() || !ItemStack.areItemsAndComponentsEqual(actual, stacks[i])) {
                return false;
            }
        }
        return true;
    }

    /** True when every stack count is conserved globally (slots may permute). */
    static boolean sameMultiset(ChestEvidence before, ChestEvidence after) {
        if (before.stacks.length != after.stacks.length) return false;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (ItemStack stack : before.stacks) {
            if (stack.isEmpty()) continue;
            String key = Registries.ITEM.getId(stack.getItem()) + "@" + stack.getComponents().hashCode();
            counts.merge(key, stack.getCount(), Integer::sum);
        }
        for (ItemStack stack : after.stacks) {
            if (stack.isEmpty()) continue;
            String key = Registries.ITEM.getId(stack.getItem()) + "@" + stack.getComponents().hashCode();
            Integer left = counts.get(key);
            if (left == null || left < stack.getCount()) return false;
            counts.put(key, left - stack.getCount());
        }
        return counts.values().stream().allMatch(v -> v == 0);
    }
}
