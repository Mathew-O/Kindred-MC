package dev.kindred.core.logistics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestOrganizationPolicyTest {

    @Test
    void classifiesItemsIntoCanonicalWarehouseCategories() {
        assertEquals(ChestOrganizationPolicy.StorageCategory.ORES_AND_VALUABLES,
                ChestOrganizationPolicy.classifyItem("minecraft:diamond"));
        assertEquals(ChestOrganizationPolicy.StorageCategory.ORES_AND_VALUABLES,
                ChestOrganizationPolicy.classifyItem("minecraft:raw_iron"));
        assertEquals(ChestOrganizationPolicy.StorageCategory.TOOLS_AND_COMBAT,
                ChestOrganizationPolicy.classifyItem("minecraft:trident"));
        assertEquals(ChestOrganizationPolicy.StorageCategory.TOOLS_AND_COMBAT,
                ChestOrganizationPolicy.classifyItem("minecraft:mace"));
        assertEquals(ChestOrganizationPolicy.StorageCategory.FOOD_AND_CROPS,
                ChestOrganizationPolicy.classifyItem("minecraft:cooked_beef"));
        assertEquals(ChestOrganizationPolicy.StorageCategory.WOOD_AND_TIMBER,
                ChestOrganizationPolicy.classifyItem("minecraft:oak_log"));
        assertEquals(ChestOrganizationPolicy.StorageCategory.STONE_AND_MASONRY,
                ChestOrganizationPolicy.classifyItem("minecraft:stone_bricks"));
        assertEquals(ChestOrganizationPolicy.StorageCategory.REDSTONE_AND_RAILS,
                ChestOrganizationPolicy.classifyItem("minecraft:powered_rail"));
        assertEquals(ChestOrganizationPolicy.StorageCategory.NETHER_AND_END,
                ChestOrganizationPolicy.classifyItem("minecraft:blaze_rod"));
    }

    @Test
    void compactsPartialStacksAndSortsByCategoryOrder() {
        var slots = List.of(
                new ChestOrganizationPolicy.SlotSnapshot(0, "minecraft:cobblestone", 32, 64, 1L),
                new ChestOrganizationPolicy.SlotSnapshot(1, "minecraft:diamond", 5, 64, 2L),
                new ChestOrganizationPolicy.SlotSnapshot(2, "minecraft:cobblestone", 20, 64, 1L)
        );

        var moves = ChestOrganizationPolicy.planChestOrganization(slots, 8);
        assertFalse(moves.isEmpty());
        // First move should merge the two partial cobblestone stacks (slot 2 -> slot 0)
        assertEquals(ChestOrganizationPolicy.ChestSlotMove.Kind.MERGE_PARTIAL_STACK, moves.getFirst().kind());
        assertEquals(2, moves.getFirst().fromSlot());
        assertEquals(0, moves.getFirst().toSlot());
        // Subsequent move should place diamond (ORES_AND_VALUABLES, order 10) ahead of cobblestone (STONE_AND_MASONRY, order 50)
        assertTrue(moves.stream().anyMatch(m -> m.kind() == ChestOrganizationPolicy.ChestSlotMove.Kind.SWAP_FOR_CATEGORY_SORT));
    }

    @Test
    void plansSurplusDepositsWhilePreservingPersonalKit() {
        var inv = List.of(
                new ChestOrganizationPolicy.SlotSnapshot(0, "minecraft:iron_sword", 1, 1, 10L),
                new ChestOrganizationPolicy.SlotSnapshot(1, "minecraft:cooked_beef", 16, 64, 11L),
                new ChestOrganizationPolicy.SlotSnapshot(9, "minecraft:cobblestone", 64, 64, 12L),
                new ChestOrganizationPolicy.SlotSnapshot(10, "minecraft:cobblestone", 64, 64, 12L),
                new ChestOrganizationPolicy.SlotSnapshot(11, "minecraft:diamond", 12, 64, 13L)
        );

        var deposits = ChestOrganizationPolicy.planSurplusDeposits(inv);
        // Keeps sword (slot 0), food (slot 1, <=32), and first stack of cobblestone (slot 9),
        // but deposits second stack of cobblestone (slot 10) and diamonds (slot 11)!
        assertEquals(2, deposits.size());
        assertTrue(deposits.stream().anyMatch(d -> d.playerSlot() == 10 && d.itemId().equals("minecraft:cobblestone")));
        assertTrue(deposits.stream().anyMatch(d -> d.playerSlot() == 11 && d.itemId().equals("minecraft:diamond")));
    }

    @Test
    void evaluatesPlayerQolAssistanceForChestYieldFoodAndTorches() {
        UUID humanId = UUID.randomUUID();
        var actions = ChestOrganizationPolicy.evaluatePlayerQolAssistance(
                humanId,
                "Alex",
                4,     // low hunger (cannot sprint)
                6.0f,  // low health
                2,     // dark cave
                true,  // human is currently using the chest
                16,
                "minecraft:cooked_beef",
                32
        );

        assertEquals(3, actions.size());
        assertTrue(actions.stream().anyMatch(a -> a.kind() == ChestOrganizationPolicy.PlayerQolAssistance.AssistKind.YIELD_CHEST_ACCESS));
        assertTrue(actions.stream().anyMatch(a -> a.kind() == ChestOrganizationPolicy.PlayerQolAssistance.AssistKind.TOSS_EMERGENCY_FOOD && a.count() == 4));
        assertTrue(actions.stream().anyMatch(a -> a.kind() == ChestOrganizationPolicy.PlayerQolAssistance.AssistKind.TOSS_TORCHES_IN_DARK && a.count() == 8));
    }
}
