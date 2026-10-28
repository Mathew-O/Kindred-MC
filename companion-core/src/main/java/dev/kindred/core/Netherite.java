package dev.kindred.core;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Netherite (E-16c), as players get it: ancient debris smelted into scrap, four scrap and four gold made into an ingot,
 * and a diamond piece upgraded at a smithing table with an ingot and an upgrade template, which is spent (a template is
 * copied from another with seven diamonds and a block of netherrack). The pieces in the order a run upgrades them: its
 * sword and pickaxe first, then its armour, chest to boots, then its axe. Pure: the adapter says what it holds.
 */
public final class Netherite {
    private Netherite() { }

    /** What a run upgrades, in the order it does. */
    public static final List<String> ORDER = List.of("sword", "pickaxe", "chestplate", "leggings", "helmet", "boots", "axe");

    /** What to do next toward its netherite, from what it holds. */
    public sealed interface Step {
        /** Upgrade the diamond piece in this slot at a smithing table. */
        record Upgrade(int slot, String piece) implements Step { }

        /** Make a netherite ingot from four scrap and four gold ingots. */
        record Ingot() implements Step { }

        /** Copy an upgrade template from the one it holds: seven diamonds and a block of netherrack. */
        record Template() implements Step { }

        /** Smelt this many ancient debris into scrap. */
        record Smelt(int debris) implements Step { }
    }

    /**
     * The next step, from what it holds ({@code held}: items by id) and its diamond pieces by slot: a template copied
     * first, keeping the one it copies from, when it has more ingots than templates and seven diamonds to spare beyond
     * {@code diamondsKept} with a block of netherrack; else an upgrade when it has an ingot and a template, its first
     * piece in {@link #ORDER}; else an ingot from four scrap and four gold; else its debris smelted, up to an ingot's
     * worth of scrap. None when nothing is to be done.
     */
    public static Optional<Step> next(Map<String, Integer> held, Map<Integer, String> diamondPieces, int diamondsKept) {
        var first = diamondPieces.entrySet().stream()
                .filter(e -> ORDER.contains(kind(e.getValue())))
                .min(Comparator.comparingInt(e -> ORDER.indexOf(kind(e.getValue()))));
        if (first.isEmpty()) return Optional.empty();
        int ingots = held.getOrDefault("minecraft:netherite_ingot", 0), templates = held.getOrDefault("minecraft:netherite_upgrade_smithing_template", 0);
        int scrap = held.getOrDefault("minecraft:netherite_scrap", 0), debris = held.getOrDefault("minecraft:ancient_debris", 0);
        if (templates >= 1 && ingots > templates && diamondPieces.size() > templates
                && held.getOrDefault("minecraft:diamond", 0) - diamondsKept >= 7 && held.getOrDefault("minecraft:netherrack", 0) >= 1) {
            return Optional.of(new Step.Template());
        }
        if (ingots >= 1 && templates >= 1) return Optional.of(new Step.Upgrade(first.get().getKey(), first.get().getValue()));
        if (scrap >= 4 && held.getOrDefault("minecraft:gold_ingot", 0) >= 4) return Optional.of(new Step.Ingot());
        if (debris >= 1 && scrap < 4) return Optional.of(new Step.Smelt(Math.min(debris, 4 - scrap)));
        return Optional.empty();
    }

    /** The kind of a piece by its id: "minecraft:diamond_sword" is a sword. */
    public static String kind(String item) {
        String path = item.substring(item.indexOf(':') + 1);
        int at = path.lastIndexOf('_');
        return at < 0 ? path : path.substring(at + 1);
    }

    /** What it says: "I upgraded my diamond sword to netherite." */
    public static String said(String piece) {
        return "I upgraded my " + piece.substring(piece.indexOf(':') + 1).replace('_', ' ') + " to netherite.";
    }
}
