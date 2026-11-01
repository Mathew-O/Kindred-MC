package dev.kindred.core.guide;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In plain terms: breaks what a player wants for later into the actual work: a bow it lacks becomes string to
 * gather and sticks to craft, then the bow. What it already holds is counted only once, so the work changes
 * as soon as it picks something up or loses something.
 *
 * Technical: Breaks what a player wants for its larger objectives into the chunks of work it takes from what
 * it holds now: a bow it lacks becomes three string to gather and three sticks to craft, then the bow;
 * thirty-two arrows become eight flint and eight feathers to gather, eight sticks, then eight crafts. What it
 * already holds counts once against all the uses it has (the same sticks serve the bow and the arrows), so
 * the chunks change as soon as it picks something up, loses something, or is given a different want. Pure and
 * deterministic.
 */
public final class NeedsAssessment {
    public enum Kind { GATHER, CRAFT }

    /**
     * One chunk of work: gather {@code count} of a need, or craft {@code count} of it (a whole number of crafts).
     * {@code objective} and {@code why} are those of the want it serves; for an input, what it goes into.
     */
    public record Chunk(Need need, Kind kind, int count, Objective objective, String why) {
        public Chunk {
            Objects.requireNonNull(need);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(objective);
            Objects.requireNonNull(why);
        }
    }

    private NeedsAssessment() { }

    /**
     * The chunks still to do for {@code wants} with {@code held} in hand: what to gather first, then what to craft, inputs
     * before what they make. A need wanted twice is wanted at the larger count; an input is wanted for every craft that
     * takes it, beyond what is held.
     */
    public static List<Chunk> chunks(List<Want> wants, Map<Need, Integer> held) {
        Objects.requireNonNull(wants);
        Objects.requireNonNull(held);
        var total = new EnumMap<Need, Integer>(Need.class);
        var first = new EnumMap<Need, Want>(Need.class);
        for (Want want : wants) {
            total.merge(want.need(), want.count(), Math::max);
            first.putIfAbsent(want.need(), want);
        }
        var usedFor = new EnumMap<Need, Set<Need>>(Need.class);
        var objectiveOf = new EnumMap<Need, Objective>(Need.class);
        var gathers = new ArrayList<Chunk>();
        var crafts = new ArrayList<Chunk>();
        // Need's order puts each product before its inputs, so an input's total is complete when it is reached.
        for (Need need : Need.values()) {
            int shortBy = total.getOrDefault(need, 0) - Math.max(0, held.getOrDefault(need, 0));
            if (shortBy <= 0) continue;
            Want own = first.get(need);
            Objective objective = own != null ? own.objective() : objectiveOf.getOrDefault(need, Objective.SURVIVAL);
            String why = own != null ? own.why() : "for the " + words(usedFor.get(need));
            var recipe = MinecraftGuide.recipe(need);
            if (recipe.isEmpty()) {
                gathers.add(new Chunk(need, Kind.GATHER, shortBy, objective, why));
                continue;
            }
            int times = (shortBy + recipe.get().yield() - 1) / recipe.get().yield();
            for (var input : recipe.get().inputs().entrySet()) {
                total.merge(input.getKey(), times * input.getValue(), Integer::sum);
                usedFor.computeIfAbsent(input.getKey(), key -> new LinkedHashSet<>()).add(need);
                objectiveOf.putIfAbsent(input.getKey(), objective);
            }
            crafts.add(new Chunk(need, Kind.CRAFT, times * recipe.get().yield(), objective, why));
        }
        var out = new ArrayList<>(gathers);
        for (int i = crafts.size() - 1; i >= 0; i--) out.add(crafts.get(i));
        return List.copyOf(out);
    }

    /** A craft that can be made once now from the needs held (one with inputs that are not needs is the adapter's call). */
    public static boolean ready(Chunk chunk, Map<Need, Integer> held) {
        if (chunk.kind() != Kind.CRAFT) return false;
        var recipe = MinecraftGuide.recipe(chunk.need()).orElseThrow();
        if (recipe.inputs().isEmpty()) return false;
        for (var input : recipe.inputs().entrySet()) if (held.getOrDefault(input.getKey(), 0) < input.getValue()) return false;
        return true;
    }

    private static String words(Set<Need> products) {
        if (products == null || products.isEmpty()) return "way ahead";
        var names = products.stream().map(n -> n == Need.ARROWS ? "arrows" : n.word()).toList();
        return names.size() == 1 ? names.getFirst() : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }
}
