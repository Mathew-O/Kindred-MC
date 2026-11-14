package dev.kindred.core.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: the player's notes on how its kit is crafted name the planks and iron that are no needs of their own,
 * so nothing is attempted with only half of what it takes.
 *
 * Technical: {@link MinecraftGuide.Recipe#others} (F-25c7a). The native guard (NativeRecipeGuardGameTests) checks them
 * against the server's recipes; this holds the guide's data and the planner's view of it.
 */
class GuideRecipesTest {
    private static MinecraftGuide.Recipe recipe(Need need) {
        return MinecraftGuide.recipe(need).orElseThrow();
    }

    @Test
    void theBedTakesThreePlanksBesideItsWool() {
        assertEquals(Map.of(Need.WOOL, 3), recipe(Need.BED).inputs());
        assertEquals(Map.of(MinecraftGuide.PLANKS, 3), recipe(Need.BED).others());
    }

    @Test
    void theShieldSwordAndSticksNameWhatIsNoNeed() {
        assertEquals(Map.of(MinecraftGuide.PLANKS, 6, "minecraft:iron_ingot", 1), recipe(Need.SHIELD).others());
        assertEquals(Map.of("minecraft:iron_ingot", 2), recipe(Need.IRON_SWORD).others());
        assertEquals(Map.of(MinecraftGuide.PLANKS, 2), recipe(Need.STICKS).others());
    }

    @Test
    void recipesOfNeedsAloneHaveNoOthers() {
        for (Need need : new Need[] {Need.BOW, Need.ARROWS, Need.GOLD_ARMOR}) assertTrue(recipe(need).others().isEmpty(), need.name());
        assertTrue(new MinecraftGuide.Recipe(Need.BOW, 1, Map.of(Need.STRING, 3), true).others().isEmpty());
    }

    @Test
    void thePlannerStillBreaksANeedDownByItsNeedsAlone() {
        // The bed's planks are the adapter's to find: what is held of the needs says the wool is ready, as before.
        var bed = new NeedsAssessment.Chunk(Need.BED, NeedsAssessment.Kind.CRAFT, 1, Objective.END_FIGHT, "");
        assertTrue(NeedsAssessment.ready(bed, Map.of(Need.WOOL, 3)));
    }
}
