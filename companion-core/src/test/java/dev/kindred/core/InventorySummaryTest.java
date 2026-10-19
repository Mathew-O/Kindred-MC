package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InventorySummaryTest {

    @Test
    void emptySummaryInitializesDefaults() {
        InventorySummary summary = InventorySummary.empty(27);
        assertEquals(0, summary.revision());
        assertEquals(27, summary.capacity());
        assertEquals(0, summary.occupiedSlots());
        assertEquals(27, summary.freeSlots());
        assertEquals(0, summary.totalItems());
        assertFalse(summary.isFull());
        assertEquals(0, summary.count("minecraft:oak_log"));
        assertTrue(summary.has("minecraft:oak_log", 0));
        assertFalse(summary.has("minecraft:oak_log", 1));
    }

    @Test
    void populatedSummaryTracksCountsAndOccupancy() {
        Map<String, Integer> counts = Map.of(
                "minecraft:oak_log", 16,
                "minecraft:iron_axe", 1
        );
        InventorySummary summary = new InventorySummary(3, 27, 2, 17, counts);
        assertEquals(3, summary.revision());
        assertEquals(27, summary.capacity());
        assertEquals(2, summary.occupiedSlots());
        assertEquals(25, summary.freeSlots());
        assertEquals(17, summary.totalItems());
        assertFalse(summary.isFull());
        assertEquals(16, summary.count("minecraft:oak_log"));
        assertEquals(1, summary.count("minecraft:iron_axe"));
        assertEquals(0, summary.count("minecraft:diamond"));
        assertTrue(summary.has("minecraft:oak_log", 10));
        assertTrue(summary.has("minecraft:oak_log", 16));
        assertFalse(summary.has("minecraft:oak_log", 17));
        assertThrows(UnsupportedOperationException.class, () -> summary.itemCounts().put("item", 1));
    }

    @Test
    void validationRejectsIllegalArguments() {
        assertThrows(IllegalArgumentException.class, () -> new InventorySummary(-1, 27, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new InventorySummary(0, 0, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new InventorySummary(0, 27, -1, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new InventorySummary(0, 27, 28, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new InventorySummary(0, 27, 0, -1, Map.of()));
        assertThrows(NullPointerException.class, () -> new InventorySummary(0, 27, 0, 0, null));
    }
}
