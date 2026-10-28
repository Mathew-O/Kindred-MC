package dev.kindred.fabric;

import dev.kindred.core.InventorySummary;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: moves real items between an older-style AI body, the ground and a chest, and checks the
 * numbers add up exactly: what left one place arrived in the other, nothing more, nothing less.
 *
 * Technical: Moves real stacks between a companion's slots, the ground, and a container with verifiable item
 * conservation. Nothing is copied into metadata; source decrease and destination increase must reconcile
 * exactly.
 */
final class ItemMoves {
    private ItemMoves() { }

    public record TransferResult(
            UUID actionId,
            String itemId,
            int requested,
            int transferred,
            int sourceBefore,
            int sourceAfter,
            int destBefore,
            int destAfter,
            boolean reconciled
    ) {
        public boolean fullySucceeded() { return transferred == requested && reconciled; }
        public String beforeEvidence() { return "{\"source\":" + sourceBefore + ",\"dest\":" + destBefore + "}"; }
        public String afterEvidence() { return "{\"transferred\":" + transferred + ",\"source\":" + sourceAfter + ",\"dest\":" + destAfter + "}"; }
    }

    public record PickupResult(
            UUID actionId,
            String itemId,
            int requested,
            int pickedUp,
            int inventoryBefore,
            int inventoryAfter,
            boolean reconciled
    ) {
        public boolean fullySucceeded() { return pickedUp == requested && reconciled; }
        public String beforeEvidence() { return "{\"inventory\":" + inventoryBefore + "}"; }
        public String afterEvidence() { return "{\"pickedUp\":" + pickedUp + ",\"inventory\":" + inventoryAfter + "}"; }
    }

    /** How many of an item a container holds. */
    static int count(Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    /** Inserts up to {@code count} and returns how many actually fit. */
    static int insert(Inventory inventory, ItemStack offered, int count) {
        int left = Math.min(count, offered.getCount());
        int moved = 0;
        for (int slot = 0; slot < inventory.size() && left > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                int place = Math.min(left, offered.getMaxCount());
                inventory.setStack(slot, offered.copyWithCount(place));
                left -= place;
                moved += place;
            } else if (ItemStack.areItemsAndComponentsEqual(stack, offered) && stack.getCount() < stack.getMaxCount()) {
                int place = Math.min(left, stack.getMaxCount() - stack.getCount());
                stack.increment(place);
                left -= place;
                moved += place;
            }
        }
        return moved;
    }

    /**
     * Verified transfer between two inventories with exact item conservation.
     * If destination cannot fit all extracted items (e.g. partial capacity or external player insertions),
     * uninserted excess is immediately returned to the source.
     */
    static TransferResult transfer(UUID actionId, Inventory source, Inventory destination, Item item, int count) {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(item, "item");
        String itemId = Registries.ITEM.getId(item).toString();
        int sourceBefore = count(source, item);
        int destBefore = count(destination, item);

        if (count <= 0) {
            return new TransferResult(actionId, itemId, 0, 0, sourceBefore, sourceBefore, destBefore, destBefore, true);
        }

        int take = Math.min(count, sourceBefore);
        if (take <= 0) {
            return new TransferResult(actionId, itemId, count, 0, sourceBefore, sourceBefore, destBefore, destBefore, true);
        }

        // Move the real stacks slot by slot so damage, enchantments and other components travel with them.
        int inserted = 0;
        int left = take;
        for (int slot = 0; slot < source.size() && left > 0; slot++) {
            ItemStack stack = source.getStack(slot);
            if (!stack.isOf(item)) continue;
            int portion = Math.min(left, stack.getCount());
            int placed = insert(destination, stack, portion);
            stack.decrement(placed);
            if (stack.isEmpty()) source.setStack(slot, ItemStack.EMPTY);
            inserted += placed;
            left -= placed;
            if (placed < portion) break; // Destination is full; nothing left the source beyond what was placed.
        }

        int sourceAfter = count(source, item);
        int destAfter = count(destination, item);
        boolean reconciled = (sourceBefore - sourceAfter == inserted) && (destAfter - destBefore == inserted);

        return new TransferResult(actionId, itemId, count, inserted, sourceBefore, sourceAfter, destBefore, destAfter, reconciled);
    }

    /** Pulls nearby dropped stacks of {@code item} into the inventory. Returns how many were picked up. */
    static int pickup(CompanionEntity body, Item item, int wanted) {
        if (wanted <= 0) return 0;
        Box area = body.getBoundingBox().expand(1.5);
        int got = 0;
        for (ItemEntity drop : ((ServerWorld) body.getWorld()).getEntitiesByClass(ItemEntity.class, area, entity -> entity.getStack().isOf(item))) {
            if (got >= wanted) break;
            ItemStack stack = drop.getStack();
            int moved = insert(body.inventory(), stack, Math.min(wanted - got, stack.getCount()));
            stack.decrement(moved);
            got += moved;
            if (stack.isEmpty()) drop.discard();
            else drop.setStack(stack);
        }
        return got;
    }

    /**
     * Attributed pickup that distinguishes skill receipts from unrelated inventory changes.
     */
    static PickupResult attributedPickup(UUID actionId, CompanionEntity body, Item item, int wanted) {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(item, "item");
        String itemId = Registries.ITEM.getId(item).toString();
        int before = count(body.inventory(), item);
        int picked = pickup(body, item, wanted);
        int after = count(body.inventory(), item);
        boolean reconciled = (after - before == picked);
        return new PickupResult(actionId, itemId, wanted, picked, before, after, reconciled);
    }
}
