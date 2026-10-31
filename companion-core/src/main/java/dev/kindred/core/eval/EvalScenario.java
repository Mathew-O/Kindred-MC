package dev.kindred.core.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In plain terms: the game an unattended evaluation run plays, as the operator wrote it in the {@code KINDRED_EVAL}
 * setting: which of the three starters play, toward which goal, whether they may break and place blocks, for how many
 * ticks, sprinted or not, with the optional model players on or off, whether they keep their things when they die, and
 * whether they learn their own lessons from their deaths and keep an atlas of the world.
 * Anything this cannot say is not an evaluation run; a person runs it by hand.
 *
 * Technical: F-26. {@code key=value} pairs separated by {@code ;}, spaces round them ignored: {@code starters}
 * (comma-separated canonical names), {@code goal} ({@code nether}, {@code end}, {@code dragon}, {@code none}),
 * {@code edits}, {@code sprint}, {@code strategist} and {@code voice} ({@code on}/{@code off}), {@code ticks} (1 to
 * ten million), {@code keep} ({@code inventory}/{@code none}), {@code lessons} and {@code atlas} ({@code on}/{@code off},
 * on unless said; E-46, F-30). An unknown key, an unknown value or a key given twice is
 * refused. A run is earned only when nothing in it is handed out or kept: the starters spawn with empty inventories,
 * edits are on under the roaming policy and nothing is kept at death; every other run is staged.
 */
public record EvalScenario(List<String> starters, Goal goal, boolean edits, long ticks, boolean sprint,
                           boolean strategist, boolean voice, Keep keep, boolean lessons, boolean atlas) {
    /** What the starters are set to do. */
    public enum Goal { NETHER, END, DRAGON, NONE }

    /** What a death keeps: the inventory (the {@code keepInventory} rule), or nothing, as in survival. */
    public enum Keep { INVENTORY, NONE }

    /** Two hours of game time at twenty ticks a second. */
    public static final long DEFAULT_TICKS = 144_000;
    public static final long MAX_TICKS = 10_000_000;
    private static final List<String> CANONICAL = List.of("Kindred", "Sol", "Echo");
    private static final Set<String> KEYS = Set.of("starters", "goal", "edits", "ticks", "sprint", "strategist", "voice", "keep", "lessons", "atlas");

    public EvalScenario {
        starters = List.copyOf(starters);
        if (starters.isEmpty()) throw new IllegalArgumentException("No starters");
        if (ticks < 1 || ticks > MAX_TICKS) throw new IllegalArgumentException("ticks must be 1 to " + MAX_TICKS + ": " + ticks);
    }

    /** The scenario {@code text} describes, its missing keys at their defaults; refused if anything in it is not understood. */
    public static EvalScenario parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Empty scenario");
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String part : text.split(";")) {
            if (part.isBlank()) continue;
            int eq = part.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("Not a key=value pair: '" + part.trim() + "'");
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim();
            if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown key '" + key + "'");
            if (pairs.put(key, value) != null) throw new IllegalArgumentException("Key given twice: '" + key + "'");
        }
        List<String> starters = new ArrayList<>();
        for (String name : pairs.getOrDefault("starters", String.join(",", CANONICAL)).split(",")) {
            if (name.isBlank()) continue;
            String canonical = CANONICAL.stream().filter(c -> c.equalsIgnoreCase(name.trim())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Not a starter: '" + name.trim() + "' (Kindred, Sol or Echo)"));
            if (starters.contains(canonical)) throw new IllegalArgumentException("Starter given twice: " + canonical);
            starters.add(canonical);
        }
        Goal goal = switch (pairs.getOrDefault("goal", "nether").toLowerCase(Locale.ROOT)) {
            case "nether" -> Goal.NETHER;
            case "end" -> Goal.END;
            case "dragon" -> Goal.DRAGON;
            case "none" -> Goal.NONE;
            default -> throw new IllegalArgumentException("Unknown goal '" + pairs.get("goal") + "' (nether, end, dragon or none)");
        };
        long ticks;
        try {
            ticks = Long.parseLong(pairs.getOrDefault("ticks", String.valueOf(DEFAULT_TICKS)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ticks is not a number: '" + pairs.get("ticks") + "'");
        }
        Keep keep = switch (pairs.getOrDefault("keep", "none").toLowerCase(Locale.ROOT)) {
            case "inventory" -> Keep.INVENTORY;
            case "none" -> Keep.NONE;
            default -> throw new IllegalArgumentException("Unknown keep '" + pairs.get("keep") + "' (inventory or none)");
        };
        return new EvalScenario(starters, goal, onOff(pairs, "edits", true), ticks, onOff(pairs, "sprint", true),
                onOff(pairs, "strategist", false), onOff(pairs, "voice", false), keep, onOff(pairs, "lessons", true), onOff(pairs, "atlas", true));
    }

    private static boolean onOff(Map<String, String> pairs, String key, boolean fallback) {
        String value = pairs.get(key);
        if (value == null) return fallback;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException(key + " must be on or off: '" + value + "'");
        };
    }

    /** Nothing handed out or kept: empty inventories, edits on, nothing kept at death. Every other run is staged. */
    public boolean earned() {
        return edits && keep == Keep.NONE;
    }

    /** The scenario in the form {@link #parse} reads, every key given, in a fixed order. */
    public String asText() {
        return "starters=" + String.join(",", starters) + "; goal=" + goal.name().toLowerCase(Locale.ROOT)
                + "; edits=" + (edits ? "on" : "off") + "; ticks=" + ticks + "; sprint=" + (sprint ? "on" : "off")
                + "; strategist=" + (strategist ? "on" : "off") + "; voice=" + (voice ? "on" : "off")
                + "; keep=" + keep.name().toLowerCase(Locale.ROOT) + "; lessons=" + (lessons ? "on" : "off") + "; atlas=" + (atlas ? "on" : "off");
    }
}
