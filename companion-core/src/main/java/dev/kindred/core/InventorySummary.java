package dev.kindred.core;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of a companion's inventory at a specific monotonic revision.
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

    public static InventorySummary empty(int capacity) {
        return new InventorySummary(0, capacity, 0, 0, Map.of());
    }

    public int count(String itemId) {
        return itemCounts.getOrDefault(itemId, 0);
    }

    public boolean has(String itemId, int amount) {
        return amount <= 0 || count(itemId) >= amount;
    }

    public int freeSlots() {
        return capacity - occupiedSlots;
    }

    public boolean isFull() {
        return occupiedSlots >= capacity;
    }
}
