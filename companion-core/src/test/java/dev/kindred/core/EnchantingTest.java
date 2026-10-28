package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantingTest {
    @Test
    void itsSwordFirstThenItsBowAndArmourTheBestOfEachAndNothingAlreadyEnchanted() {
        var held = List.of(
                new Enchanting.Piece(0, "minecraft:iron_sword", Map.of()),
                new Enchanting.Piece(1, "minecraft:diamond_sword", Map.of()),
                new Enchanting.Piece(2, "minecraft:diamond_pickaxe", Map.of()),
                new Enchanting.Piece(3, "minecraft:diamond_chestplate", Map.of()),
                new Enchanting.Piece(4, "minecraft:bow", Map.of()),
                new Enchanting.Piece(5, "minecraft:stone_sword", Map.of()));
        assertEquals(1, Enchanting.next(held).orElseThrow().slot(), "the diamond sword before the iron one");
        var swordDone = List.of(
                new Enchanting.Piece(1, "minecraft:diamond_sword", Map.of("minecraft:sharpness", 3)),
                new Enchanting.Piece(2, "minecraft:diamond_pickaxe", Map.of()),
                new Enchanting.Piece(3, "minecraft:diamond_chestplate", Map.of()),
                new Enchanting.Piece(4, "minecraft:bow", Map.of()));
        assertEquals(4, Enchanting.next(swordDone).orElseThrow().slot(), "the bow next, then the armour, the pickaxe last");
        assertTrue(Enchanting.next(List.of(new Enchanting.Piece(0, "minecraft:golden_sword", Map.of()),
                new Enchanting.Piece(1, "minecraft:wooden_pickaxe", Map.of()), new Enchanting.Piece(2, "minecraft:diamond", Map.of()))).isEmpty(),
                "nothing worth enchanting: gold, wood, and what is no tool");
    }

    @Test
    void itGoesToItsTableWithThirtyLevelsAndThreeLapis() {
        assertTrue(Enchanting.ready(30, 3));
        assertFalse(Enchanting.ready(29, 64));
        assertFalse(Enchanting.ready(40, 2));
    }

    @Test
    void ofTheOffersTheBestItCanPayForByWhatTheHintIsWorthOnThePiece() {
        var sword = new Enchanting.Piece(0, "minecraft:diamond_sword", Map.of());
        var offers = List.of(new Enchanting.Offer(0, 8, "minecraft:knockback", 1), new Enchanting.Offer(1, 19, "minecraft:sharpness", 2),
                new Enchanting.Offer(2, 30, "minecraft:smite", 4));
        // Sharpness II (worth 3 a level) over Smite IV (1 a level) and a costlier button.
        assertEquals(Optional.of(1), Enchanting.choose(sword, offers, 30, 3));
        // Twenty levels: the top offer is beyond it, the middle one still not.
        assertEquals(Optional.of(1), Enchanting.choose(sword, offers, 20, 3));
        // Lapis for one button only.
        assertEquals(Optional.of(0), Enchanting.choose(sword, offers, 30, 1));
        // Nothing it can pay for.
        assertTrue(Enchanting.choose(sword, offers, 5, 3).isEmpty());
        // As much worth either way: the costlier offer, which brings more with it.
        var even = List.of(new Enchanting.Offer(0, 9, "minecraft:unbreaking", 1), new Enchanting.Offer(2, 30, "minecraft:unbreaking", 1));
        assertEquals(Optional.of(2), Enchanting.choose(sword, even, 30, 3));
    }

    @Test
    void whatItSaysOfIt() {
        assertEquals("I enchanted my diamond sword with Sharpness III, Unbreaking II.",
                Enchanting.said("minecraft:diamond_sword", Map.of("minecraft:sharpness", 3, "minecraft:unbreaking", 2)));
        assertEquals("I enchanted my bow with Power.", Enchanting.said("minecraft:bow", Map.of("minecraft:power", 1)));
    }
}
