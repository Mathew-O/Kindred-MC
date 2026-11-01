package dev.kindred.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In plain terms: how a player brews the potions its run wants at a brewing stand of its own: fire resistance
 * for the Nether, strength and slow falling for the dragon, and healing, three at a time. It says what goes
 * in next from what the player holds.
 *
 * Technical: Brewing (E-16b), as players brew at a stand of their own: water bottles filled at water, made
 * awkward with nether wart, then the potion a run wants from the awkward base with its ingredient, three
 * bottles at a time, the stand fuelled with blaze powder. The potions a run wants and in what order: fire
 * resistance for the Nether, strength and slow falling for the dragon, healing for any fight. Pure: the
 * adapter says what it holds and what the stand holds.
 */
public final class Brewing {
    private Brewing() { }

    /** The potions a run brews, in the order it wants them, each with what it is brewed from the awkward base with. */
    public enum Potion {
        FIRE_RESISTANCE("minecraft:fire_resistance", "minecraft:magma_cream"),
        STRENGTH("minecraft:strength", "minecraft:blaze_powder"),
        SLOW_FALLING("minecraft:slow_falling", "minecraft:phantom_membrane"),
        HEALING("minecraft:healing", "minecraft:glistering_melon_slice");

        /** Its id as a potion's contents name it. */
        public final String id;
        /** What turns an awkward potion into it. */
        public final String ingredient;

        Potion(String id, String ingredient) {
            this.id = id;
            this.ingredient = ingredient;
        }
    }

    /** How many of each potion a run keeps: a stand's three. */
    public static final int KEEP = 3;

    /** A step at the stand: what goes in its three bottle slots, its ingredient, and whether it needs fuel first. */
    public record Brew(String bottles, String ingredient, String yields, String why) { }

    /**
     * The next brew, from what it holds ({@code held}: items by id, and potions by their contents' id, "minecraft:water"
     * for a water bottle, "minecraft:awkward" for an awkward one): the first potion it wants and has fewer than {@link
     * #KEEP} of whose ingredient it holds; its awkward potions brewed into it if it has three, else its water bottles made
     * awkward with nether wart, three at a time. None when it has nothing to brew it with. Glass bottles are filled at
     * water first (the adapter's step, before this one).
     */
    public static Optional<Brew> next(Map<String, Integer> held, Map<String, Integer> potions) {
        for (Potion p : Potion.values()) {
            if (potions.getOrDefault(p.id, 0) >= KEEP || held.getOrDefault(p.ingredient, 0) < 1) continue;
            if (potions.getOrDefault("minecraft:awkward", 0) >= KEEP) {
                return Optional.of(new Brew("minecraft:awkward", p.ingredient, p.id, "Three awkward potions brewed into " + name(p.id)));
            }
            if (potions.getOrDefault("minecraft:water", 0) >= KEEP && held.getOrDefault("minecraft:nether_wart", 0) >= 1) {
                return Optional.of(new Brew("minecraft:water", "minecraft:nether_wart", "minecraft:awkward",
                        "Three water bottles made awkward with nether wart, for " + name(p.id)));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** How many glass bottles to fill at water before brewing: up to three, while it has awkward ones to make and none filled. */
    public static int toFill(Map<String, Integer> held, Map<String, Integer> potions) {
        if (potions.getOrDefault("minecraft:water", 0) >= KEEP || potions.getOrDefault("minecraft:awkward", 0) >= KEEP) return 0;
        if (held.getOrDefault("minecraft:nether_wart", 0) < 1) return 0;
        boolean wanted = false;
        for (Potion p : Potion.values()) if (potions.getOrDefault(p.id, 0) < KEEP && held.getOrDefault(p.ingredient, 0) >= 1) wanted = true;
        if (!wanted) return 0;
        return Math.min(KEEP - potions.getOrDefault("minecraft:water", 0), held.getOrDefault("minecraft:glass_bottle", 0));
    }

    /** Whether the stand needs blaze powder put in: none left of its fuel. */
    public static boolean needsFuel(int fuel) {
        return fuel <= 0;
    }

    /** What it says of it: "I brewed three potions of fire resistance." */
    public static String said(String potion, int count) {
        String n = count == 1 ? "a potion" : (count == 2 ? "two potions" : "three potions");
        return "I brewed " + n + " of " + name(potion) + ".";
    }

    private static String name(String id) {
        return id.substring(id.indexOf(':') + 1).replace('_', ' ');
    }

    /** The potions in the order a run wants them, by id. */
    public static List<String> order() {
        return java.util.Arrays.stream(Potion.values()).map(p -> p.id).toList();
    }
}
