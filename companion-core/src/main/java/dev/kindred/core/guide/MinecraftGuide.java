package dev.kindred.core.guide;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What vanilla 1.21.1 says about the needs: how each crafted one is made, where each gathered one comes from, and what
 * each larger objective needs in hand before it begins. The local planner reads it to break an objective into the
 * gathering and crafting it takes ({@link NeedsAssessment}); the strategist's guide says the same in words. Pure data.
 */
public final class MinecraftGuide {
    /**
     * A recipe: {@code yield} of the result from one craft of {@code inputs}. Inputs that are not needs (planks for
     * sticks, iron for a shield) are the adapter's to find, from the wood and iron the player carries.
     */
    public record Recipe(Need result, int yield, Map<Need, Integer> inputs, boolean table) {
        public Recipe {
            Objects.requireNonNull(result);
            if (yield < 1) throw new IllegalArgumentException("yield " + yield);
            inputs = Map.copyOf(inputs);
        }
    }

    /** At least {@code count} of a need in hand when an objective begins, and why. */
    public record Requirement(Need need, int count, String why) { }

    private static final Map<Need, Recipe> RECIPES = new EnumMap<>(Map.of(
            Need.BOW, new Recipe(Need.BOW, 1, Map.of(Need.STRING, 3, Need.STICKS, 3), true),
            Need.ARROWS, new Recipe(Need.ARROWS, 4, Map.of(Need.FLINT, 1, Need.STICKS, 1, Need.FEATHERS, 1), true),
            Need.STICKS, new Recipe(Need.STICKS, 4, Map.of(), false),
            Need.SHIELD, new Recipe(Need.SHIELD, 1, Map.of(), true)));

    private static final Map<Need, String> SOURCES = new EnumMap<>(Map.of(
            Need.STRING, "spiders (none to two a kill; calm in daylight) and cobwebs broken with a sword",
            Need.FEATHERS, "chickens (none to two a kill)",
            Need.FLINT, "gravel dug up (one in ten; gravel set down and dug again until it drops)",
            Need.ARROWS, "crafted, four at a time; skeletons drop none to two",
            Need.WATER_BUCKET, "an iron bucket (three ingots) filled at still water",
            Need.BLOCKS, "stone dug with a pickaxe (it drops cobblestone), or netherrack",
            Need.FOOD, "animals hunted: cows, pigs, sheep, chickens, rabbits",
            Need.STICKS, "two planks make four",
            Need.SHIELD, "six planks and an iron ingot at a crafting table",
            Need.BOW, "three string and three sticks at a crafting table"));

    private static final Map<Objective, List<Requirement>> REQUIREMENTS = new EnumMap<>(Map.of(
            Objective.NETHER_TRIP, List.of(
                    new Requirement(Need.FOOD, 12, "the Nether has little to eat"),
                    new Requirement(Need.BLOCKS, 48, "to bridge lava and wall off")),
            Objective.END_FIGHT, List.of(
                    new Requirement(Need.BOW, 1, "to shoot the End crystals off their pillars; they heal the dragon"),
                    new Requirement(Need.ARROWS, 32, "a few for each of the ten crystals, and for the dragon in flight"),
                    new Requirement(Need.BLOCKS, 64, "to pillar up to the two caged crystals and wall off endermen"),
                    new Requirement(Need.WATER_BUCKET, 1, "to break a fall from a pillar; endermen keep out of water"),
                    new Requirement(Need.FOOD, 16, "there is nothing to eat in the End"))));

    private MinecraftGuide() { }

    /** How a need is crafted, or nothing for one that is gathered. */
    public static Optional<Recipe> recipe(Need need) {
        return Optional.ofNullable(RECIPES.get(Objects.requireNonNull(need)));
    }

    /** Where a need comes from, in words. */
    public static String source(Need need) {
        return SOURCES.getOrDefault(Objects.requireNonNull(need), "");
    }

    /** What an objective needs in hand before it begins; empty for those the route's own stages provide for. */
    public static List<Requirement> requirements(Objective objective) {
        return REQUIREMENTS.getOrDefault(Objects.requireNonNull(objective), List.of());
    }
}
