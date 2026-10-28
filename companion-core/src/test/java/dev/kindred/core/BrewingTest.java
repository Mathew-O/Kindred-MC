package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrewingTest {
    @Test
    void waterMadeAwkwardThenBrewedIntoThePotionItWantsFirst() {
        var held = Map.of("minecraft:nether_wart", 2, "minecraft:magma_cream", 1, "minecraft:blaze_powder", 4);
        var awkward = Brewing.next(held, Map.of("minecraft:water", 3)).orElseThrow();
        assertEquals("minecraft:water", awkward.bottles());
        assertEquals("minecraft:nether_wart", awkward.ingredient());
        var fire = Brewing.next(held, Map.of("minecraft:awkward", 3)).orElseThrow();
        assertEquals("minecraft:magma_cream", fire.ingredient());
        assertEquals("minecraft:fire_resistance", fire.yields());
        // Three of fire resistance kept: strength next, from blaze powder.
        var strength = Brewing.next(held, Map.of("minecraft:fire_resistance", 3, "minecraft:awkward", 3)).orElseThrow();
        assertEquals("minecraft:strength", strength.yields());
    }

    @Test
    void nothingToBrewWithoutTheIngredientOrTheWart() {
        assertTrue(Brewing.next(Map.of("minecraft:nether_wart", 3), Map.of("minecraft:water", 3)).isEmpty(), "no ingredient for any potion");
        assertTrue(Brewing.next(Map.of("minecraft:magma_cream", 1), Map.of("minecraft:water", 3)).isEmpty(), "no wart to make them awkward");
        assertTrue(Brewing.next(Map.of("minecraft:magma_cream", 1, "minecraft:nether_wart", 1), Map.of("minecraft:water", 1)).isEmpty(),
                "a stand's three bottles at a time");
    }

    @Test
    void bottlesFilledOnlyWhileThereIsSomethingToBrew() {
        var held = Map.of("minecraft:glass_bottle", 5, "minecraft:nether_wart", 1, "minecraft:magma_cream", 1);
        assertEquals(3, Brewing.toFill(held, Map.of()));
        assertEquals(2, Brewing.toFill(held, Map.of("minecraft:water", 1)));
        assertEquals(0, Brewing.toFill(held, Map.of("minecraft:awkward", 3)));
        assertEquals(0, Brewing.toFill(Map.of("minecraft:glass_bottle", 5, "minecraft:magma_cream", 1), Map.of()), "no wart");
        assertEquals(0, Brewing.toFill(Map.of("minecraft:glass_bottle", 5, "minecraft:nether_wart", 1), Map.of()), "nothing wanted to brew");
    }

    @Test
    void whatItSays() {
        assertEquals("I brewed three potions of fire resistance.", Brewing.said("minecraft:fire_resistance", 3));
        assertTrue(Brewing.needsFuel(0));
    }
}
