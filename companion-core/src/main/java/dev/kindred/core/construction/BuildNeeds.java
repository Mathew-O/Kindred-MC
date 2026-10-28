package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A build's needs as a queue (E-08c, the owner's note of 2026-10-23). A player short of blocks for a build does not
 * stop to fetch each one as it comes up. It builds what it can from what it holds, then fetches the whole shortfall
 * in one trip: what its own storage holds, what it can gather, and what it crafts from those, then comes back and
 * finishes. Two things it does at once: making what it can from what it already carries (no walk at all), and taking
 * what its own storage close by holds (easy to get). Pure: one pass at a time, from what it holds and what is left to
 * place; it remembers only whether it is on a trip.
 */
public final class BuildNeeds {
    /**
     * A way to make an item, as vanilla 1.21.1 has it: how many one craft makes, what one craft takes, and whether it
     * needs a crafting table (a 3x3 grid) or is made in the player's own 2x2 grid.
     */
    public record Recipe(String item, int makes, Map<String, Integer> takes, boolean table) {
        public Recipe {
            takes = Map.copyOf(takes);
            if (makes < 1 || takes.isEmpty()) throw new IllegalArgumentException("A recipe makes something from something");
        }
    }

    /** Woods with the whole family of planks, stairs, slabs, fences, gates, doors and trapdoors. */
    public static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry");

    /** What is gathered, not made: cut, mined or dug. */
    public static final Set<String> GATHERED = Set.of("cobblestone");

    /**
     * The recipe for {@code item}, if it is one of the blocks and parts a build is made of. A recipe that takes any
     * planks (sticks, a crafting table) takes {@code wood}'s.
     */
    public static Optional<Recipe> recipe(String item, String wood) {
        String id = item.startsWith("minecraft:") ? item.substring("minecraft:".length()) : item;
        String planks = "minecraft:" + wood + "_planks";
        for (String w : WOODS) {
            String p = "minecraft:" + w + "_planks";
            if (id.equals(w + "_planks")) return Optional.of(new Recipe(item(id), 4, Map.of("minecraft:" + w + "_log", 1), false));
            if (id.equals(w + "_stairs")) return Optional.of(new Recipe(item(id), 4, Map.of(p, 6), true));
            if (id.equals(w + "_slab")) return Optional.of(new Recipe(item(id), 6, Map.of(p, 3), true));
            if (id.equals(w + "_fence")) return Optional.of(new Recipe(item(id), 3, Map.of(p, 4, "minecraft:stick", 2), true));
            if (id.equals(w + "_fence_gate")) return Optional.of(new Recipe(item(id), 1, Map.of(p, 2, "minecraft:stick", 4), true));
            if (id.equals(w + "_door")) return Optional.of(new Recipe(item(id), 3, Map.of(p, 6), true));
            if (id.equals(w + "_trapdoor")) return Optional.of(new Recipe(item(id), 2, Map.of(p, 6), true));
        }
        return Optional.ofNullable(switch (id) {
            case "stick" -> new Recipe(item(id), 4, Map.of(planks, 2), false);
            case "crafting_table" -> new Recipe(item(id), 1, Map.of(planks, 4), false);
            case "chest" -> new Recipe(item(id), 1, Map.of(planks, 8), true);
            case "ladder" -> new Recipe(item(id), 3, Map.of("minecraft:stick", 7), true);
            case "wooden_pickaxe" -> new Recipe(item(id), 1, Map.of(planks, 3, "minecraft:stick", 2), true);
            case "cobblestone_stairs" -> new Recipe(item(id), 4, Map.of("minecraft:cobblestone", 6), true);
            case "cobblestone_slab" -> new Recipe(item(id), 6, Map.of("minecraft:cobblestone", 3), true);
            case "cobblestone_wall" -> new Recipe(item(id), 6, Map.of("minecraft:cobblestone", 6), true);
            case "stone_brick_stairs" -> new Recipe(item(id), 4, Map.of("minecraft:stone_bricks", 6), true);
            case "stone_brick_slab" -> new Recipe(item(id), 6, Map.of("minecraft:stone_bricks", 3), true);
            case "stone_bricks" -> new Recipe(item(id), 4, Map.of("minecraft:stone", 4), false);
            case "glass_pane" -> new Recipe(item(id), 16, Map.of("minecraft:glass", 6), true);
            case "bookshelf" -> new Recipe(item(id), 1, Map.of(planks, 6, "minecraft:book", 3), true);
            case "furnace" -> new Recipe(item(id), 1, Map.of("minecraft:cobblestone", 8), true);
            default -> null;
        });
    }

    private static String item(String id) { return "minecraft:" + id; }

    /** Whether a player gathers {@code item} itself: logs it cuts, cobblestone it mines. */
    public static boolean gathered(String item) {
        String id = item.startsWith("minecraft:") ? item.substring("minecraft:".length()) : item;
        if (GATHERED.contains(id)) return true;
        for (String w : WOODS) if (id.equals(w + "_log")) return true;
        return false;
    }

    /**
     * How a shortfall is met: what to take from storage, what to gather, and what to craft, inputs before what they go
     * into, each craft once per entry; and what none of these gives.
     */
    public record Plan(Map<String, Integer> take, Map<String, Integer> gather, List<Recipe> crafts, Set<String> unobtainable) {
        public Plan {
            take = Map.copyOf(take);
            gather = Map.copyOf(gather);
            crafts = List.copyOf(crafts);
            unobtainable = Set.copyOf(unobtainable);
        }

        public boolean empty() { return take.isEmpty() && gather.isEmpty() && crafts.isEmpty(); }
    }

    /**
     * The plan for {@code shortfall}: what the player holds goes first, then what its storage holds, then what it
     * makes from those or from what it gathers. Leftovers of a craft (four sticks when two were wanted) count toward
     * what else wants them.
     *
     * @param pickaxe whether it holds a pickaxe; cobblestone wants one, made first if not
     */
    public static Plan plan(Map<String, Integer> shortfall, Map<String, Integer> held, Map<String, Integer> stored, String wood, boolean pickaxe) {
        var have = new LinkedHashMap<>(held);
        var store = new LinkedHashMap<>(stored);
        var take = new LinkedHashMap<String, Integer>();
        var gather = new LinkedHashMap<String, Integer>();
        var crafts = new ArrayList<Recipe>();
        var unobtainable = new LinkedHashSet<String>();
        for (var e : shortfall.entrySet()) meet(e.getKey(), e.getValue(), have, store, take, gather, crafts, unobtainable, wood, 0);
        if (gather.containsKey("minecraft:cobblestone") && !pickaxe) {
            meet("minecraft:wooden_pickaxe", 1, have, store, take, gather, crafts, unobtainable, wood, 0);
        }
        return new Plan(take, gather, crafts, unobtainable);
    }

    private static void meet(String item, int count, Map<String, Integer> have, Map<String, Integer> store, Map<String, Integer> take,
                             Map<String, Integer> gather, List<Recipe> crafts, Set<String> unobtainable, String wood, int depth) {
        int use = Math.min(have.getOrDefault(item, 0), count);
        if (use > 0) have.merge(item, -use, Integer::sum);
        count -= use;
        if (count <= 0) return;
        int stored = Math.min(store.getOrDefault(item, 0), count);
        if (stored > 0) {
            store.merge(item, -stored, Integer::sum);
            take.merge(item, stored, Integer::sum);
            count -= stored;
        }
        if (count <= 0) return;
        var recipe = depth < 6 ? recipe(item, wood) : Optional.<Recipe>empty();
        if (recipe.isPresent()) {
            var r = recipe.get();
            int times = (count + r.makes() - 1) / r.makes();
            for (var input : r.takes().entrySet()) {
                meet(input.getKey(), input.getValue() * times, have, store, take, gather, crafts, unobtainable, wood, depth + 1);
            }
            for (int i = 0; i < times; i++) crafts.add(r);
            int left = times * r.makes() - count;
            if (left > 0) have.merge(item, left, Integer::sum);
            return;
        }
        if (gathered(item)) gather.merge(item, count, Integer::sum);
        else unobtainable.add(item);
    }

    /** What to do on this pass. */
    public sealed interface Step {
        /** Go on building with what it holds. */
        record Build(String why) implements Step { }

        /** Take these from its own storage, in one visit. */
        record Take(Map<String, Integer> items, String why) implements Step { }

        /** Craft one of this. */
        record Craft(Recipe recipe, String why) implements Step { }

        /** Gather this many of this. */
        record Gather(String item, int count, String why) implements Step { }

        /** Nothing more to be had for the rest: these are not to be got by any way it has. */
        record Short(Set<String> items, String why) implements Step { }
    }

    private boolean trip;

    /** Whether it is out fetching the shortfall. */
    public boolean onTrip() { return trip; }

    /** Everything the build takes is in hand: any trip for it is over. */
    public void allInHand() { trip = false; }

    /**
     * The next step for the build's needs.
     *
     * @param needed      what the rest of the build places, by item
     * @param held        what the player holds; what the build places is set aside, and only the rest is spare to craft from
     * @param placeable   whether it can place anything now with what it holds
     * @param stored      what its own storage holds, if it knows
     * @param storageNear whether that storage is close enough to step over to
     */
    public Step next(Map<String, Integer> needed, Map<String, Integer> held, boolean placeable, Map<String, Integer> stored,
                     boolean storageNear, String wood, boolean pickaxe) {
        var shortfall = new LinkedHashMap<String, Integer>();
        var spare = new LinkedHashMap<>(held);
        for (var e : needed.entrySet()) {
            int have = held.getOrDefault(e.getKey(), 0);
            if (have < e.getValue()) shortfall.put(e.getKey(), e.getValue() - have);
            spare.merge(e.getKey(), -Math.min(have, e.getValue()), Integer::sum);
        }
        spare.values().removeIf(v -> v <= 0);
        if (shortfall.isEmpty()) {
            trip = false;
            return new Step.Build("Everything the build takes is in hand");
        }
        var plan = plan(shortfall, spare, stored, wood, pickaxe);
        // Easy to get: its own storage close by, or made from what it carries with no walk at all.
        if (!plan.take().isEmpty() && storageNear) return new Step.Take(plan.take(), "From its own storage close by");
        var ready = plan.crafts().stream().filter(r -> covered(r, spare)).findFirst();
        if (!trip && ready.isPresent() && plan.gather().isEmpty() && plan.take().isEmpty()) {
            return new Step.Craft(ready.get(), "Made from what it carries");
        }
        // Otherwise it builds what it can first, and only then goes for the rest, all of it in one trip.
        if (!trip && placeable) return new Step.Build("Building what it can with what it holds first");
        if (plan.empty()) {
            trip = false;
            return plan.unobtainable().isEmpty() ? new Step.Build("Nothing more to fetch")
                    : new Step.Short(plan.unobtainable(), "Nothing it can do gets " + String.join(", ", plan.unobtainable()));
        }
        trip = true;
        if (!plan.take().isEmpty()) return new Step.Take(plan.take(), "From its own storage");
        if (!plan.gather().isEmpty()) {
            var first = plan.gather().entrySet().iterator().next();
            return new Step.Gather(first.getKey(), first.getValue(), "All the " + name(first.getKey()) + " the rest of the build takes, in one go");
        }
        if (ready.isPresent()) return new Step.Craft(ready.get(), "Made for the rest of the build");
        // Gathered and taken, yet nothing to craft from what it holds: an input went astray; the trip starts over.
        trip = false;
        return new Step.Short(Set.of(plan.crafts().getFirst().item()), "The makings of " + name(plan.crafts().getFirst().item()) + " are not at hand");
    }

    private static boolean covered(Recipe recipe, Map<String, Integer> held) {
        for (var input : recipe.takes().entrySet()) if (held.getOrDefault(input.getKey(), 0) < input.getValue()) return false;
        return true;
    }

    private static String name(String item) {
        return (item.startsWith("minecraft:") ? item.substring("minecraft:".length()) : item).replace('_', ' ');
    }
}
