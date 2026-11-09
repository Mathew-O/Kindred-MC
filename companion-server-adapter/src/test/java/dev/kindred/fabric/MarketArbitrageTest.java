package dev.kindred.fabric;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kindred.core.market.MarketBook;
import dev.kindred.core.market.MarketTable;
import dev.kindred.core.market.Pricing;
import dev.kindred.core.market.Recipe;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: walks every recipe in Minecraft 1.21.1 (crafting, smelting, blasting, smoking, campfire cooking,
 * stonecutting and smithing upgrades) against the market's prices and fails if any one turns coins into more coins:
 * buying what it takes from the market, making it and selling what it makes, at the baseline and at two hundred
 * random states of the market.
 *
 * Technical: The arbitrage walk (M-06g), a plain JUnit test over the game's own recipe and item-tag files read from the
 * Minecraft jar on the classpath (no game bootstrap). A loop is open only where every ingredient has an option the
 * market sells; it is paid for at the market's asking price of that option and sold at what the market pays for the
 * output. Fuel is not counted, which favours the would-be arbitrageur.
 */
class MarketArbitrageTest {
    @Test
    void noRecipeMintsCoins() throws IOException {
        var recipes = vanillaRecipes();
        var pricing = new Pricing(MarketTable.bundled(), recipes);
        long now = 1_000L * 3_600_000;
        var books = new ArrayList<MarketBook>();
        books.add(MarketBook.EMPTY);
        var random = new Random(20261108);
        var roots = new ArrayList<>(pricing.table().roots().keySet());
        for (int i = 0; i < 200; i++) {
            var states = new HashMap<String, MarketBook.State>();
            for (String root : roots) {
                if (random.nextInt(3) == 0) continue;
                double stock = (random.nextDouble() * 2 - 1) * Math.pow(10, random.nextInt(5));
                long updated = now - (long) (random.nextDouble() * 48 * 3_600_000);
                states.put(root, new MarketBook.State(stock, updated, 0, 0));
            }
            books.add(MarketBook.EMPTY.with(states));
        }
        int priced = 0, open = 0, naive = 0;
        var failures = new ArrayList<String>();
        for (Recipe recipe : recipes) {
            boolean anyOpen = false;
            for (MarketBook book : books) {
                var out = pricing.price(book, recipe.output(), now);
                if (out.isEmpty()) break;
                double cost = 0;
                boolean buyable = true;
                for (Set<String> ingredient : recipe.ingredients()) {
                    double cheapest = Double.MAX_VALUE;
                    for (String option : ingredient) {
                        var own = pricing.own(book, option, now);
                        if (own.isEmpty() || !own.get().roots().keySet().stream().allMatch(r -> pricing.root(book, r).orElseThrow().buyable())) continue;
                        cheapest = Math.min(cheapest, Pricing.buyPrice(own.get().mid()));
                    }
                    if (cheapest == Double.MAX_VALUE) { buyable = false; break; }
                    cost += cheapest;
                }
                if (!buyable) break;
                anyOpen = true;
                double paid = recipe.count() * out.get().mid() * (1 - Pricing.SPREAD);
                if (paid > cost + 1e-6) failures.add(recipe.id() + ": costs " + cost + ", sells for " + paid);
                // The control: priced by its own price alone, without the cap by its recipes, an output would mint.
                var own = pricing.own(book, recipe.output(), now);
                if (own.isPresent() && recipe.count() * own.get().mid() * (1 - Pricing.SPREAD) > cost + 1e-6) naive++;
            }
            if (pricing.price(MarketBook.EMPTY, recipe.output(), now).isPresent()) priced++;
            if (anyOpen) open++;
        }
        System.out.println("Arbitrage walk: " + recipes.size() + " recipes, " + priced + " with a priced output, " + open
                + " open to buying every ingredient, " + books.size() + " market states, " + failures.size() + " minting; " + naive
                + " (recipe, state) pairs that would mint if outputs kept their own price");
        assertTrue(naive > 0, "the walk can find a minting loop: without the recipe cap it does");
        assertTrue(recipes.size() > 1000, "the whole recipe book read: " + recipes.size());
        assertTrue(failures.isEmpty(), "Loops that mint coins: " + failures.subList(0, Math.min(10, failures.size())));
    }

    /** Every recipe in the game's data, ingredients resolved through the item tags. */
    static List<Recipe> vanillaRecipes() throws IOException {
        var url = MarketArbitrageTest.class.getClassLoader().getResource("data/minecraft/recipe/stick.json");
        if (url == null) throw new IllegalStateException("No Minecraft data on the classpath");
        URI jar = URI.create(url.toString().substring(0, url.toString().indexOf("!/")));
        try (FileSystem fs = FileSystems.newFileSystem(jar, Map.of())) {
            var tags = new HashMap<String, JsonArray>();
            try (var files = Files.walk(fs.getPath("/data/minecraft/tags/item"))) {
                for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                    String name = "minecraft:" + fs.getPath("/data/minecraft/tags/item").relativize(p).toString().replace(".json", "");
                    tags.put(name, JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("values"));
                }
            }
            var out = new ArrayList<Recipe>();
            try (var files = Files.walk(fs.getPath("/data/minecraft/recipe"))) {
                for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                    var json = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                    var recipe = recipe(p.getFileName().toString().replace(".json", ""), json, tags);
                    if (recipe != null) out.add(recipe);
                }
            }
            return out;
        }
    }

    private static Recipe recipe(String id, JsonObject json, Map<String, JsonArray> tags) {
        String type = json.get("type").getAsString();
        var ingredients = new ArrayList<Set<String>>();
        switch (type) {
            case "minecraft:crafting_shaped" -> {
                var key = json.getAsJsonObject("key");
                for (JsonElement row : json.getAsJsonArray("pattern")) {
                    for (char c : row.getAsString().toCharArray()) if (c != ' ') ingredients.add(options(key.get(String.valueOf(c)), tags));
                }
            }
            case "minecraft:crafting_shapeless" -> { for (JsonElement e : json.getAsJsonArray("ingredients")) ingredients.add(options(e, tags)); }
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking", "minecraft:stonecutting" ->
                    ingredients.add(options(json.get("ingredient"), tags));
            case "minecraft:smithing_transform" -> {
                ingredients.add(options(json.get("template"), tags));
                ingredients.add(options(json.get("base"), tags));
                ingredients.add(options(json.get("addition"), tags));
            }
            default -> { return null; }
        }
        var result = json.getAsJsonObject("result");
        int count = result.has("count") ? result.get("count").getAsInt() : 1;
        if (ingredients.stream().anyMatch(Set::isEmpty)) return null;
        return new Recipe(id, result.get("id").getAsString(), count, ingredients);
    }

    private static Set<String> options(JsonElement element, Map<String, JsonArray> tags) {
        var out = new TreeSet<String>();
        if (element.isJsonArray()) for (JsonElement e : element.getAsJsonArray()) out.addAll(options(e, tags));
        else {
            var o = element.getAsJsonObject();
            if (o.has("item")) out.add(o.get("item").getAsString());
            if (o.has("tag")) out.addAll(tag(o.get("tag").getAsString(), tags, new LinkedHashSet<>()));
        }
        return out;
    }

    private static Set<String> tag(String name, Map<String, JsonArray> tags, Set<String> seen) {
        var out = new TreeSet<String>();
        if (!seen.add(name) || !tags.containsKey(name)) return out;
        for (JsonElement v : tags.get(name)) {
            String value = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
            if (value.startsWith("#")) out.addAll(tag(value.substring(1), tags, seen));
            else out.add(value);
        }
        return out;
    }
}
