package dev.kindred.core.rescue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RescueRecipesTest {
    private static final RescueSlice.Cell OBSIDIAN = RescueSlice.Cell.solid("obsidian", false);

    /** The pit: a shaft one wide and three deep in obsidian, open sky over it; {@code ceiling} glass that far over its feet. */
    private static RescueSlice pit(int ceiling, RescueSlice.Cell ground) {
        Map<List<Integer>, RescueSlice.Cell> set = new HashMap<>();
        for (int dx = -RescueSlice.RADIUS; dx <= RescueSlice.RADIUS; dx++)
            for (int dz = -RescueSlice.RADIUS; dz <= RescueSlice.RADIUS; dz++)
                for (int dy = 0; dy <= 2; dy++) if (dx != 0 || dz != 0) set.put(List.of(dx, dy, dz), OBSIDIAN);
        if (ceiling > 0) set.put(List.of(0, ceiling, 0), new RescueSlice.Cell(RescueSlice.Kind.SOLID, "glass", true, true, false));
        return RescueSlice.perceive((dx, dy, dz) -> {
            var cell = set.get(List.of(dx, dy, dz));
            return cell != null ? cell : dy < 0 ? ground : RescueSlice.Cell.OPEN;
        });
    }

    private static RescueSignature pitSignature() {
        return RescueSignature.of(pit(0, OBSIDIAN), 15, true, 0);
    }

    @Test
    void thePitsSignatureIsWalledOnEverySideHardRoomyOverheadWithALedgeAndAShaft() {
        var s = pitSignature();
        assertEquals("NSEW", s.walls());
        assertEquals(4, s.headroom());
        assertEquals(RescueSlice.Kind.SOLID, s.under());
        assertTrue(s.hemmedHard());
        assertTrue(s.ledge(), "The rim three up is within a pillar's reach");
        assertTrue(s.shaft());
        assertFalse(s.dark());
        assertFalse(s.liquidAbove() || s.liquidBelow() || s.liquidBeside());
        assertEquals("walls=NSEW;head=4;under=solid;liquid=;hard=true;dark=false;ledge=true;shaft=true;blocks=true;pickaxe=0", s.key());
    }

    @Test
    void aSceneThatDiffersInAFeatureDoesNotMatchAndNoCoordinateIsInTheKey() {
        var book = new RescueRecipes();
        book.learned(pitSignature(), List.of("pillar 3", "walk north 2"), "server");
        assertTrue(book.match(pitSignature()).isPresent());
        var lowCeiling = RescueSignature.of(pit(3, OBSIDIAN), 15, true, 0);
        assertNotEquals(pitSignature().key(), lowCeiling.key());
        assertEquals(1, lowCeiling.headroom());
        assertTrue(book.match(lowCeiling).isEmpty());
        assertTrue(book.match(RescueSignature.of(pit(0, OBSIDIAN), 15, false, 0)).isEmpty(), "No blocks to pillar with: another kind of scene");
        assertTrue(book.match(RescueSignature.of(pit(0, OBSIDIAN), 3, true, 0)).isEmpty(), "Dark");
        assertFalse(pitSignature().key().matches(".*-?\\d{3,}.*"), "No coordinate: " + pitSignature().key());
    }

    @Test
    void aRecipeThatWorksGainsWeightAndTwoFailuresInARowRetireIt() {
        var book = new RescueRecipes();
        var recipe = book.learned(pitSignature(), List.of("pillar 3", "walk north 2"), "server");
        assertEquals(1, recipe.weight());
        book.worked(recipe);
        assertEquals(2, book.match(pitSignature()).orElseThrow().weight());
        book.failed(recipe);
        assertEquals(1, book.match(pitSignature()).orElseThrow().failures());
        book.worked(recipe);
        assertEquals(0, book.match(pitSignature()).orElseThrow().failures(), "A success forgives the failures before it");
        book.failed(recipe);
        book.failed(recipe);
        assertTrue(book.match(pitSignature()).isEmpty(), "Two in a row retire it");
        assertTrue(book.revision() > 0);
    }

    @Test
    void theHeaviestMatchingRecipeIsTriedFirstAndTheSameStepsLearnedAgainAreOne() {
        var book = new RescueRecipes();
        book.learned(pitSignature(), List.of("pillar 3", "walk north 2"), "server");
        book.learned(pitSignature(), List.of("pillar 3", "walk south 2"), "server");
        book.learned(pitSignature(), List.of("pillar 3", "walk south 2"), "server");
        assertEquals(2, book.all().size());
        assertEquals(List.of("pillar 3", "walk south 2"), book.match(pitSignature()).orElseThrow().steps());
    }

    @Test
    void theBookHoldsFiveHundredTheLightestGivingWay() {
        var book = new RescueRecipes();
        var heavy = book.learned(pitSignature(), List.of("pillar 3"), "server");
        book.worked(heavy);
        String[] walls = {"----", "N---", "-S--", "--E-", "---W", "NS--", "N-E-", "N--W", "-SE-", "-S-W", "--EW", "NSE-", "NS-W", "N-EW", "-SEW", "NSEW"};
        for (int i = 0; i < RescueRecipes.MAX + 20; i++) {
            // Each a scene of its own: its walls, its room overhead, its pickaxe and its light, all different.
            book.learned(new RescueSignature(walls[i % 16], (i / 16) % 5, RescueSlice.Kind.SOLID, false, false, false, false, (i / 320) % 2 == 1,
                    false, false, true, (i / 80) % 4), List.of("walk east " + (1 + i % 8)), "server");
        }
        assertEquals(RescueRecipes.MAX, book.all().size());
        assertTrue(book.all().stream().anyMatch(r -> r.weight() == 2 && r.steps().equals(List.of("pillar 3"))), "The heavier kept");
        var loaded = new ArrayList<RescueRecipes.Recipe>(book.all());
        loaded.add(new RescueRecipes.Recipe("x", List.of("fly up 3"), 1, 0, "server"));
        assertEquals(RescueRecipes.MAX, new RescueRecipes(loaded).all().size(), "Bounded as read");
        assertEquals(0, new RescueRecipes(List.of(new RescueRecipes.Recipe("x", List.of("fly up 3"), 1, 0, "server"))).all().size(),
                "A recipe not of the vocabulary is left out");
    }
}
