package dev.kindred.fabric;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * In plain terms: a snapshot of everything an AI player carries, taken before an action and compared after,
 * so the mod can tell whether any item was lost, gained or only moved.
 *
 * Technical: Server-thread comparison snapshots only; workers receive just the fingerprint string.
 */
record InventoryEvidence(ItemStack[] stacks, String description) {
    /** A snapshot of the player's inventory as it is now, with a short fingerprint of it. */
    static InventoryEvidence capture(NativePlayerBody body) {
        return capture(body.entity().getInventory());
    }

    /** As above, of an inventory itself (a fixture's player, F-31g1). */
    static InventoryEvidence capture(net.minecraft.entity.player.PlayerInventory inventory) {
        var stacks = new ItemStack[inventory.size()];
        for (int i = 0; i < stacks.length; i++) stacks[i] = inventory.getStack(i).copy();
        return new InventoryEvidence(stacks, describe(stacks));
    }

    /** A player inventory's size: 36 main slots, 4 armour, the offhand. */
    static final int SLOTS = 41;

    /**
     * In plain terms: the same fingerprint {@link #capture} takes of a player's inventory, read from its native save on
     * disk with no body in the world: what a settlement after a crash compares with an action's before-evidence (F-31g1).
     *
     * Technical: the save's {@code Inventory} list read as {@code PlayerInventory.readNbt} reads it ({@code Slot} 0..35 the
     * main inventory, 100..103 the armour, 150 the offhand, as unsigned bytes), each stack decoded by
     * {@code ItemStack.fromNbt}, and fingerprinted slot by slot as a live inventory is.
     */
    static String describeSave(net.minecraft.nbt.NbtCompound save, net.minecraft.registry.RegistryWrapper.WrapperLookup registries) {
        var stacks = new ItemStack[SLOTS];
        java.util.Arrays.fill(stacks, ItemStack.EMPTY);
        var list = save.getList("Inventory", net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
        for (int k = 0; k < list.size(); k++) {
            var tag = list.getCompound(k);
            int slot = tag.getByte("Slot") & 255;
            int index = slot < 36 ? slot : slot >= 100 && slot < 104 ? 36 + slot - 100 : slot == 150 ? 40 : -1;
            if (index >= 0) stacks[index] = ItemStack.fromNbt(registries, tag).orElse(ItemStack.EMPTY);
        }
        return describe(stacks);
    }

    private static String describe(ItemStack[] stacks) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < stacks.length; i++) {
                var stack = stacks[i];
                String data = i + ":" + Registries.ITEM.getId(stack.getItem()) + ":" + stack.getCount() + ":" + stack.getComponents() + "\n";
                digest.update(data.getBytes(StandardCharsets.UTF_8));
            }
            return "native owned inventory slots=" + stacks.length + "; sha256=" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** How many items the snapshot holds in all. */
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

    /** Everything held before is still held, at least as many of each: anything new was only picked up. */
    boolean onlyGained(NativePlayerBody body) {
        var have = new java.util.HashMap<String, Integer>();
        var inventory = body.entity().getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) have.merge(key(stack), stack.getCount(), Integer::sum);
        }
        var held = new java.util.HashMap<String, Integer>();
        for (ItemStack stack : stacks) if (!stack.isEmpty()) held.merge(key(stack), stack.getCount(), Integer::sum);
        for (var entry : held.entrySet()) if (have.getOrDefault(entry.getKey(), 0) < entry.getValue()) return false;
        return true;
    }

    /**
     * An item as held, wear aside: armour struck while a block was being broken (staged dragon run 2, the dragon's
     * hits on iron armour mid-break) or a tool worn by a swing is the same item, not one lost and another gained. A
     * worn-out item that broke is gone from the count, and still counts as lost.
     */
    private static String key(ItemStack stack) {
        if (stack.isDamageable() && stack.contains(DataComponentTypes.DAMAGE)) {
            var unworn = stack.copy();
            unworn.remove(DataComponentTypes.DAMAGE);
            return Registries.ITEM.getId(stack.getItem()) + "|" + unworn.getComponents();
        }
        return Registries.ITEM.getId(stack.getItem()) + "|" + stack.getComponents();
    }

    /** Whether the inventory is exactly as it was, slot for slot. */
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
