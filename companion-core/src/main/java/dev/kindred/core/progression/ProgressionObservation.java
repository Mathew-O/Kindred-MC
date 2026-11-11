package dev.kindred.core.progression;

import dev.kindred.core.Capabilities;
import dev.kindred.core.VanillaIds;

import dev.kindred.core.InventorySummary;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: what the progression map looks at: a player's real inventory, whether a crafting table and
 * furnace are nearby, which dimension it is in, and a few other facts about its surroundings.
 *
 * Technical: Immutable observation of physical player and workstation state evaluated by the milestone graph.
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
                hasAssignedLogPatch, false, false, false, VanillaIds.OVERWORLD, false);
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
                hasAssignedLogPatch, hasNearbyFarmland, hasNearbyBed, hasCultivatedFarm, VanillaIds.OVERWORLD, false);
    }

    public ProgressionObservation {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(inventory, "inventory");
        currentDimension = currentDimension != null ? currentDimension : VanillaIds.OVERWORLD;
    }

    /** Whether the player is in the Nether. */
    public boolean isInNether() {
        return VanillaIds.THE_NETHER.equals(currentDimension);
    }

    /** Whether the player is in the Overworld. */
    public boolean isInOverworld() {
        return VanillaIds.OVERWORLD.equals(currentDimension);
    }

    /** How many of an item it carries. */
    public int count(String itemId) {
        return inventory.count(itemId);
    }

    /** Whether it carries at least this many of an item. */
    public boolean has(String itemId, int amount) {
        return inventory.has(itemId, amount);
    }

    /** How many it carries of what is in this group. */
    public int count(VanillaIds.Group group) {
        return inventory.count(group);
    }

    /** Whether it carries at least this many of what is in this group. */
    public boolean has(VanillaIds.Group group, int amount) {
        return inventory.has(group, amount);
    }

    /** Whether it carries a tool of this kind at this tier or better. */
    public boolean hasTool(String kind, int minimumTier) {
        return inventory.hasTool(kind, minimumTier);
    }

    /** Whether it carries a tool of this kind with a tier from {@code minimumTier} to {@code maximumTier}. */
    public boolean hasTool(String kind, int minimumTier, int maximumTier) {
        return inventory.hasTool(kind, minimumTier, maximumTier);
    }

    /** Whether it has a bed placed nearby. */
    public boolean hasBed() {
        return hasNearbyBed || hasBedItem();
    }

    /** Whether it carries a bed. */
    public boolean hasBedItem() {
        for (String item : inventory.itemCounts().keySet()) {
            if (item.endsWith("_bed")) return true;
        }
        return false;
    }

    /** Whether it carries a hoe. */
    public boolean hasHoe() {
        return hasTool(Capabilities.Tool.HOE, 0);
    }
}
