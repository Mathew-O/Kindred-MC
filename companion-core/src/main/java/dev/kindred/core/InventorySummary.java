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

    /** How many it carries of everything in this group (F-38: #minecraft:logs, not a list of logs). */
    public int count(VanillaIds.Group group) {
        Capabilities facts = Capabilities.current();
        int total = 0;
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (facts.is(group, entry.getKey())) total += entry.getValue();
        }
        return total;
    }

    /** Whether it carries at least this many of what is in this group. */
    public boolean has(VanillaIds.Group group, int amount) {
        return amount <= 0 || count(group) >= amount;
    }

    /** Whether it carries a tool of this kind at this tier or better ({@link Capabilities.Tool}). */
    public boolean hasTool(String kind, int minimumTier) {
        return hasTool(kind, minimumTier, Integer.MAX_VALUE);
    }

    /** Whether it carries a tool of this kind with a tier from {@code minimumTier} to {@code maximumTier}. */
    public boolean hasTool(String kind, int minimumTier, int maximumTier) {
        Capabilities facts = Capabilities.current();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (entry.getValue() <= 0) continue;
            var tool = facts.tool(entry.getKey());
            if (tool.isPresent() && tool.get().kind().equals(kind)
                    && tool.get().tier() >= minimumTier && tool.get().tier() <= maximumTier) return true;
        }
        return false;
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
