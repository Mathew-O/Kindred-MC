package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: checks how a player upgrades its diamond gear to netherite: its sword first, an ingot made
 * from scrap and gold and the scrap from ancient debris, and a spare upgrade template copied before the last
 * is used, unless its diamonds are needed for its run.
 */
class NetheriteTest {
    private static final Map<Integer, String> PIECES = Map.of(3, "minecraft:diamond_chestplate", 0, "minecraft:diamond_sword", 1, "minecraft:diamond_pickaxe");

    @Test
    void itsSwordUpgradedFirstWithAnIngotAndATemplate() {
        var step = Netherite.next(Map.of("minecraft:netherite_ingot", 1, "minecraft:netherite_upgrade_smithing_template", 1), PIECES, 0).orElseThrow();
        var upgrade = assertInstanceOf(Netherite.Step.Upgrade.class, step);
        assertEquals(0, upgrade.slot());
        assertEquals("minecraft:diamond_sword", upgrade.piece());
    }

    @Test
    void anIngotFromScrapAndGoldAndScrapFromDebris() {
        assertInstanceOf(Netherite.Step.Ingot.class, Netherite.next(Map.of("minecraft:netherite_scrap", 4, "minecraft:gold_ingot", 4), PIECES, 0).orElseThrow());
        var smelt = assertInstanceOf(Netherite.Step.Smelt.class, Netherite.next(Map.of("minecraft:ancient_debris", 6, "minecraft:netherite_scrap", 1), PIECES, 0).orElseThrow());
        assertEquals(3, smelt.debris(), "an ingot's worth of scrap, not all its debris");
        assertTrue(Netherite.next(Map.of("minecraft:netherite_scrap", 4, "minecraft:gold_ingot", 3), PIECES, 0).isEmpty(), "short of gold");
        assertTrue(Netherite.next(Map.of("minecraft:netherite_ingot", 1), PIECES, 0).isEmpty(), "no template: nothing to do with its ingot");
        assertTrue(Netherite.next(Map.of("minecraft:netherite_ingot", 1, "minecraft:netherite_upgrade_smithing_template", 1), Map.of(), 0).isEmpty(),
                "no diamond piece to upgrade");
    }

    @Test
    void aTemplateCopiedBeforeTheLastOneIsSpentWithDiamondsToSpare() {
        var held = Map.of("minecraft:netherite_ingot", 2, "minecraft:netherite_upgrade_smithing_template", 1, "minecraft:diamond", 9, "minecraft:netherrack", 3);
        assertInstanceOf(Netherite.Step.Template.class, Netherite.next(held, PIECES, 0).orElseThrow());
        // Its diamonds kept for its run: no copy, the one template spent on its sword.
        assertInstanceOf(Netherite.Step.Upgrade.class, Netherite.next(held, PIECES, 5).orElseThrow());
    }

    @Test
    void kindsAndWhatItSays() {
        assertEquals("sword", Netherite.kind("minecraft:diamond_sword"));
        assertEquals("I upgraded my diamond sword to netherite.", Netherite.said("minecraft:diamond_sword"));
    }
}
