package dev.kindred.fabric;

import dev.kindred.core.guide.MinecraftGuide;
import dev.kindred.core.guide.Need;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.TreeMap;

/**
 * In plain terms: the player's own notes on how its End-fight kit is crafted say exactly what the game says, so it never
 * goes to make something with half of what it takes.
 *
 * Technical: F-25c7a's guard. For every recipe {@link MinecraftGuide} knows, vanilla's recipe from the server's recipe
 * manager, its slots read as needs where every item that fills a slot satisfies one need, else as planks
 * ({@link MinecraftGuide#PLANKS}) or the item's id, is compared with the guide's {@code inputs} and {@code others}: an
 * ingredient missing or extra, a count, the yield or the table, named by its recipe. The bed's three planks were missing,
 * and a player with the wool and only logs set its table down for a bed it could not make. One gap is known and kept
 * exact here: the iron sword's stick, a need left out of {@code inputs} (see F-25c7a's evidence).
 */
public final class NativeRecipeGuardGameTests implements FabricGameTest {
    /** The vanilla recipe each need is made by: one of the beds and the golden helmet stand for their kinds. */
    private static final Map<String, String> VANILLA = Map.of("BOW", "minecraft:bow", "ARROWS", "minecraft:arrow", "STICKS", "minecraft:stick",
            "SHIELD", "minecraft:shield", "IRON_SWORD", "minecraft:iron_sword", "BED", "minecraft:white_bed", "GOLD_ARMOR", "minecraft:golden_helmet");
    /** What the guide leaves out of a recipe on purpose, as the guard expects it: the ingredient and its count. */
    private static final Map<String, Map<String, Integer>> KNOWN_GAPS = Map.of("IRON_SWORD", Map.of("STICKS", 1));

    @GameTest(templateName = FixtureGround.FIELD, batchId = "recipeGuard", tickLimit = 20)
    public void theGuidesRecipesAreVanillasOwn(TestContext context) {
        var server = context.getWorld().getServer();
        var registries = context.getWorld().getRegistryManager();
        var wrong = new StringBuilder();
        int checked = 0;
        for (Need need : Need.values()) {
            var recipe = MinecraftGuide.recipe(need);
            if (recipe.isEmpty()) continue;
            String id = VANILLA.get(need.name());
            context.assertTrue(id != null, "No vanilla recipe named for the guide's " + need.name() + ": add it to VANILLA");
            var entry = server.getRecipeManager().get(Identifier.of(id));
            context.assertTrue(entry.isPresent() && entry.get().value() instanceof CraftingRecipe, "No crafting recipe " + id);
            var crafting = (CraftingRecipe) entry.get().value();
            var vanilla = new TreeMap<String, Integer>();
            for (Ingredient ingredient : crafting.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                vanilla.merge(slot(ingredient), 1, Integer::sum);
            }
            var guide = new TreeMap<String, Integer>();
            recipe.get().inputs().forEach((n, c) -> guide.put(n.name(), c));
            guide.putAll(recipe.get().others());
            KNOWN_GAPS.getOrDefault(need.name(), Map.of()).forEach((k, c) -> guide.merge(k, c, Integer::sum));
            int yield = crafting.getResult(registries).getCount();
            boolean table = !crafting.fits(2, 2);
            if (!vanilla.equals(guide) || yield != recipe.get().yield() || table != recipe.get().table()) {
                wrong.append(need.name()).append(" (").append(id).append("): vanilla ").append(vanilla).append(" makes ").append(yield)
                        .append(table ? " at a table" : " by hand").append("; the guide ").append(guide).append(" makes ").append(recipe.get().yield())
                        .append(recipe.get().table() ? " at a table" : " by hand").append(". ");
            }
            checked++;
        }
        KindredMod.LOGGER.info("Recipe guard: {} of the guide's recipes checked against the server's{}", checked, wrong.isEmpty() ? "" : ": " + wrong);
        context.assertTrue(wrong.isEmpty(), "The guide's recipes differ from vanilla's: " + wrong);
        context.complete();
    }

    /** A slot as the guide names it: the need every item filling it satisfies, else planks, else the one item's id. */
    private static String slot(Ingredient ingredient) {
        ItemStack[] stacks = ingredient.getMatchingStacks();
        for (Need need : Need.values()) {
            boolean all = stacks.length > 0;
            for (ItemStack stack : stacks) all &= satisfies(need, stack);
            if (all) return need.name();
        }
        boolean planks = stacks.length > 0;
        for (ItemStack stack : stacks) planks &= stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS);
        if (planks) return MinecraftGuide.PLANKS;
        if (stacks.length == 1) return Registries.ITEM.getId(stacks[0].getItem()).toString();
        var ids = new StringBuilder("?");
        for (ItemStack stack : stacks) ids.append(Registries.ITEM.getId(stack.getItem())).append(' ');
        return ids.toString().trim();
    }

    /** Whether {@code stack} is what {@code need} names as satisfying it: a tag, or ids (a component need is none here). */
    private static boolean satisfies(Need need, ItemStack stack) {
        String by = need.satisfiedBy();
        if (by == null || by.isBlank() || by.contains(" component")) return false;
        for (String each : by.trim().split("\\s+")) {
            if (each.startsWith("#") ? stack.isIn(TagKey.of(RegistryKeys.ITEM, Identifier.of(each.substring(1))))
                    : Registries.ITEM.getId(stack.getItem()).toString().equals(each)) return true;
        }
        return false;
    }
}
