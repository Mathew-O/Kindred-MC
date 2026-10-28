package dev.kindred.core;

import java.util.Map;
import java.util.Objects;

/**
 * In plain terms: a snapshot of what a player is carrying (how many slots are used, and how much of each
 * item), so the brain can plan without touching the real inventory.
 *
 * Technical: Immutable snapshot of a companion's inventory at a specific monotonic revision.
 * Used by workers and decision logic without referencing live Minecraft inventory or world objects.
 */
public record InventorySummary(long revision, int capacity, int occupiedSlots, int totalItems, Map<String, Integer> itemCounts) {
    public InventorySummary {
        if (revision < 0) throw new IllegalArgumentException("Revision cannot be negative");
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
        if (occupiedSlots < 0 || occupiedSlots > capacity) throw new IllegalArgumentException("Occupied slots out of range: " + occupiedSlots + " / " + capacity);
        if (totalItems < 0) throw new IllegalArgumentException("Total items cannot be negative");
        itemCounts = Map.copyOf(Objects.requireNonNull(itemCounts, "itemCounts"));
    }

    /** An empty inventory with this many slots. */
    public static InventorySummary empty(int capacity) {
        return new InventorySummary(0, capacity, 0, 0, Map.of());
    }

    /** How many of this item it carries. */
    public int count(String itemId) {
        return itemCounts.getOrDefault(itemId, 0);
    }

    /** Whether it carries at least this many of this item. */
    public boolean has(String itemId, int amount) {
        return amount <= 0 || count(itemId) >= amount;
    }

    /** How many slots are still empty. */
    public int freeSlots() {
        return capacity - occupiedSlots;
    }

    /** Whether every slot is used. */
    public boolean isFull() {
        return occupiedSlots >= capacity;
    }
}
