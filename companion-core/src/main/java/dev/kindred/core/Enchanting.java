package dev.kindred.core;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In plain terms: how a player enchants its gear at a table of its own: which piece is next, weapons first
 * and the best material first; when it has the levels and lapis to go; and which of the table's three offers
 * is worth most for that piece.
 *
 * Technical: Enchanting (E-16a), as players do it at a table of their own: the pieces a run wants enchanted
 * and in what order (its sword for Sharpness, its bow for Power, its armour for Protection, its boots for
 * Feather Falling, its pickaxe for Efficiency), the best of each first; when it is worth going to the table
 * (thirty levels, the most a table asks, and three lapis, so that the top offer is open to it); and of the
 * three offers a table shows for a piece, the one to take: the best it can pay for, by what the hinted
 * enchantment is worth on that piece. Pure: the adapter says what it holds and what the table offers.
 */
public final class Enchanting {
    private Enchanting() { }

    /** The levels it waits for before it goes to its table: what the top offer asks with fifteen bookshelves. */
    public static final int LEVELS = 30;
    /** The lapis the top offer takes. */
    public static final int LAPIS = 3;

    /** What a run enchants, in the order it does: its weapons first, then its armour, then its pickaxe. */
    public enum Kind {
        SWORD("sword", "minecraft:sharpness"), BOW("bow", "minecraft:power"), CHESTPLATE("chestplate", "minecraft:protection"),
        LEGGINGS("leggings", "minecraft:protection"), HELMET("helmet", "minecraft:protection"), BOOTS("boots", "minecraft:feather_falling"),
        PICKAXE("pickaxe", "minecraft:efficiency");

        /** What a player calls it. */
        public final String noun;
        /** The enchantment a run wants on it most. */
        public final String wants;

        Kind(String noun, String wants) {
            this.noun = noun;
            this.wants = wants;
        }

        /** The kind of an item by its id ("minecraft:diamond_sword"), or none for what a run does not enchant. */
        public static Optional<Kind> of(String item) {
            String path = item.substring(item.indexOf(':') + 1);
            for (Kind kind : values()) if (path.equals(kind.noun) || path.endsWith("_" + kind.noun)) return Optional.of(kind);
            return Optional.empty();
        }
    }

    /** A piece it holds: its inventory slot, its item id, and the enchantments it already has. */
    public record Piece(int slot, String item, Map<String, Integer> enchantments) {
        public Piece {
            enchantments = Map.copyOf(enchantments);
        }
    }

    /**
     * One of a table's three offers for a piece, as its screen shows it: the button (0 to 2, which also costs that many
     * lapis and levels, plus one), the level it asks the player to have, and the one enchantment it hints at, with its
     * level; none hinted when the table shows nothing there.
     */
    public record Offer(int button, int requires, String hint, int hintLevel) { }

    /** How good a material is, for the best of each kind first: netherite, diamond, then the rest. */
    private static int material(String item) {
        String path = item.substring(item.indexOf(':') + 1);
        if (path.startsWith("netherite_")) return 0;
        if (path.startsWith("diamond_")) return 1;
        if (path.startsWith("iron_")) return 2;
        return path.equals("bow") ? 1 : 3;
    }

    /**
     * The piece to enchant next, of those it holds: one with no enchantment yet, of a kind a run enchants, its kinds in
     * the run's order and the best material first; none when nothing is left to enchant. Wood, stone, gold and leather
     * it leaves (soon replaced; gold wears out fast).
     */
    public static Optional<Piece> next(List<Piece> held) {
        return held.stream()
                .filter(p -> p.enchantments().isEmpty() && Kind.of(p.item()).isPresent() && material(p.item()) <= 2)
                .min(Comparator.comparingInt((Piece p) -> Kind.of(p.item()).orElseThrow().ordinal()).thenComparingInt(p -> material(p.item())));
    }

    /** Whether it has what the table's top offer takes: the levels and the lapis. */
    public static boolean ready(int levels, int lapis) {
        return levels >= LEVELS && lapis >= LAPIS;
    }

    /** What an enchantment is worth on a piece of {@code kind}: the run's want three, one that helps it two, any other one. */
    static int worth(Kind kind, String enchantment) {
        if (enchantment == null) return 0;
        if (enchantment.equals(kind.wants)) return 3;
        return switch (enchantment) {
            case "minecraft:unbreaking" -> 2;
            case "minecraft:protection", "minecraft:feather_falling", "minecraft:fire_protection", "minecraft:projectile_protection" ->
                    kind == Kind.BOOTS || kind == Kind.HELMET || kind == Kind.CHESTPLATE || kind == Kind.LEGGINGS ? 2 : 1;
            case "minecraft:looting", "minecraft:fortune", "minecraft:infinity", "minecraft:sweeping_edge" -> 2;
            default -> 1;
        };
    }

    /**
     * Of the table's offers for {@code piece}, the button to press: one it can pay for (the levels it asks, and as many
     * lapis and levels as the button's number and one), the one whose hinted enchantment is worth most on the piece at
     * its level, the costlier offer where two are worth as much (a costlier offer brings more enchantments with it);
     * none when it can pay for none.
     */
    public static Optional<Integer> choose(Piece piece, List<Offer> offers, int levels, int lapis) {
        var kind = Kind.of(piece.item());
        if (kind.isEmpty()) return Optional.empty();
        return offers.stream()
                .filter(o -> o.requires() > 0 && o.requires() <= levels && o.button() + 1 <= levels && o.button() + 1 <= lapis)
                .max(Comparator.comparingInt((Offer o) -> worth(kind.get(), o.hint()) * Math.max(1, o.hintLevel()) + o.button()))
                .map(Offer::button);
    }

    /** What it says of it: "I enchanted my diamond sword with Sharpness III." */
    public static String said(String item, Map<String, Integer> gained) {
        String noun = item.substring(item.indexOf(':') + 1).replace('_', ' ');
        var parts = gained.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> title(e.getKey()) + (e.getValue() > 1 ? " " + roman(e.getValue()) : "")).toList();
        return "I enchanted my " + noun + " with " + String.join(", ", parts) + ".";
    }

    private static String title(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        var words = path.split("_");
        var out = new StringBuilder();
        for (String w : words) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    private static String roman(int n) {
        return switch (n) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }
}
