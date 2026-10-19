package dev.kindred.core.progression;

import dev.kindred.core.InventorySummary;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable observation of physical player and workstation state evaluated by the milestone graph.
 */
public record ProgressionObservation(
        UUID companionId,
        long tick,
        InventorySummary inventory,
        boolean hasNearbyCraftingTable,
        boolean hasNearbyFurnace,
        boolean hasSmeltOutputWaiting,
        boolean canSmelt,
        boolean isNight,
        boolean hasShelter,
        boolean hasAssignedStonePatch,
        boolean hasAssignedLogPatch,
        boolean hasNearbyFarmland,
        boolean hasNearbyBed,
        boolean hasCultivatedFarm,
        String currentDimension,
        boolean hasAssignedNetherWartPatch
) {
    public ProgressionObservation(
            UUID companionId, long tick, InventorySummary inventory,
            boolean hasNearbyCraftingTable, boolean hasNearbyFurnace,
            boolean hasSmeltOutputWaiting, boolean canSmelt, boolean isNight,
            boolean hasShelter, boolean hasAssignedStonePatch, boolean hasAssignedLogPatch,
            boolean hasNearbyFarmland, boolean hasNearbyBed, boolean hasCultivatedFarm,
            String currentDimension
    ) {
        this(companionId, tick, inventory, hasNearbyCraftingTable, hasNearbyFurnace,
                hasSmeltOutputWaiting, canSmelt, isNight, hasShelter, hasAssignedStonePatch,
                hasAssignedLogPatch, hasNearbyFarmland, hasNearbyBed, hasCultivatedFarm,
                currentDimension, false);
    }
    public ProgressionObservation(
            UUID companionId,
            long tick,
            InventorySummary inventory,
            boolean hasNearbyCraftingTable,
            boolean hasNearbyFurnace,
            boolean hasSmeltOutputWaiting,
            boolean canSmelt,
            boolean isNight,
            boolean hasShelter,
            boolean hasAssignedStonePatch,
            boolean hasAssignedLogPatch
    ) {
        this(companionId, tick, inventory, hasNearbyCraftingTable, hasNearbyFurnace,
                hasSmeltOutputWaiting, canSmelt, isNight, hasShelter, hasAssignedStonePatch,
                hasAssignedLogPatch, false, false, false, "minecraft:overworld", false);
    }

    public ProgressionObservation(
            UUID companionId,
            long tick,
            InventorySummary inventory,
            boolean hasNearbyCraftingTable,
            boolean hasNearbyFurnace,
            boolean hasSmeltOutputWaiting,
            boolean canSmelt,
            boolean isNight,
            boolean hasShelter,
            boolean hasAssignedStonePatch,
            boolean hasAssignedLogPatch,
            boolean hasNearbyFarmland,
            boolean hasNearbyBed,
            boolean hasCultivatedFarm
    ) {
        this(companionId, tick, inventory, hasNearbyCraftingTable, hasNearbyFurnace,
                hasSmeltOutputWaiting, canSmelt, isNight, hasShelter, hasAssignedStonePatch,
                hasAssignedLogPatch, hasNearbyFarmland, hasNearbyBed, hasCultivatedFarm, "minecraft:overworld", false);
    }

    public ProgressionObservation {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(inventory, "inventory");
        currentDimension = currentDimension != null ? currentDimension : "minecraft:overworld";
    }

    public boolean isInNether() {
        return "minecraft:the_nether".equals(currentDimension);
    }

    public boolean isInOverworld() {
        return "minecraft:overworld".equals(currentDimension);
    }

    public int count(String itemId) {
        return inventory.count(itemId);
    }

    public boolean has(String itemId, int amount) {
        return inventory.has(itemId, amount);
    }

    public boolean hasBed() {
        return hasNearbyBed || hasBedItem();
    }

    public boolean hasBedItem() {
        for (String item : inventory.itemCounts().keySet()) {
            if (item.endsWith("_bed")) return true;
        }
        return false;
    }

    public boolean hasHoe() {
        return has("minecraft:wooden_hoe", 1) || has("minecraft:stone_hoe", 1)
                || has("minecraft:iron_hoe", 1) || has("minecraft:diamond_hoe", 1)
                || has("minecraft:golden_hoe", 1) || has("minecraft:netherite_hoe", 1);
    }
}
