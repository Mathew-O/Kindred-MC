package dev.kindred.core.construction;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A block in an exact state, as Minecraft writes one: {@code minecraft:oak_stairs[facing=north,half=bottom]} (E-08a).
 * Properties are kept sorted, so two specs of the same state are equal. Pure.
 */
public record BlockSpec(String block, Map<String, String> properties) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");

    /**
     * Properties that follow from a block's neighbours or from play, not from how it was placed: a fence's or a pane's
     * connections, a stair's corner shape, grass under snow, leaves' distance, a furnace alight, water in a block.
     * Two states that differ only in these are the same block placed the same way.
     */
    public static final Set<String> DERIVED = Set.of("north", "south", "east", "west", "up", "shape", "waterlogged", "snowy",
            "distance", "persistent", "power", "powered", "lit", "open", "occupied", "attached", "disarmed", "in_wall",
            "triggered", "has_book", "bottom", "age", "stage", "moisture", "level", "note", "instrument", "signal_fire");

    public BlockSpec {
        block = Objects.requireNonNull(block).toLowerCase(Locale.ROOT);
        if (!block.contains(":")) block = "minecraft:" + block;
        if (!ID.matcher(block).matches()) throw new IllegalArgumentException("Not a block id: " + block);
        var sorted = new TreeMap<String, String>();
        for (var entry : properties.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT), value = entry.getValue().toLowerCase(Locale.ROOT);
            if (!NAME.matcher(key).matches() || !NAME.matcher(value).matches()) throw new IllegalArgumentException("Not a property: " + key + "=" + value);
            sorted.put(key, value);
        }
        properties = Collections.unmodifiableSortedMap(sorted);
    }

    public static BlockSpec of(String block) {
        return new BlockSpec(block, Map.of());
    }

    /** A spec from Minecraft's own form, {@code id} or {@code id[key=value,...]}. */
    public static BlockSpec parse(String text) {
        Objects.requireNonNull(text);
        String s = text.trim();
        int open = s.indexOf('[');
        if (open < 0) return of(s);
        if (!s.endsWith("]")) throw new IllegalArgumentException("Unclosed properties: " + text);
        var properties = new TreeMap<String, String>();
        String inner = s.substring(open + 1, s.length() - 1);
        if (!inner.isBlank()) {
            for (String pair : inner.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("Not a property: " + pair);
                properties.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return new BlockSpec(s.substring(0, open), properties);
    }

    public String property(String name) {
        return properties.get(name);
    }

    public BlockSpec with(String name, String value) {
        var next = new TreeMap<>(properties);
        next.put(name, value);
        return new BlockSpec(block, next);
    }

    /** Air in any of its forms. */
    public boolean air() {
        return block.equals("minecraft:air") || block.equals("minecraft:cave_air") || block.equals("minecraft:void_air");
    }

    /** The same block placed the same way: equal but for {@link #DERIVED} properties. */
    public boolean sameAs(BlockSpec other) {
        if (other == null || !block.equals(other.block)) return false;
        for (String key : union(properties.keySet(), other.properties.keySet())) {
            if (DERIVED.contains(key)) continue;
            if (!Objects.equals(properties.get(key), other.properties.get(key))) return false;
        }
        return true;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new java.util.TreeSet<>(a);
        all.addAll(b);
        return all;
    }

    /**
     * This state turned clockwise by {@code quarterTurns} about the vertical, as the blueprint it is in turns:
     * horizontal facings go round (north, east, south, west), an x or z axis swaps on an odd turn, a sign's or
     * banner's sixteenth rotation goes on by four a turn, and the four side properties go round with it.
     */
    public BlockSpec rotated(int quarterTurns) {
        int turns = Math.floorMod(quarterTurns, 4);
        if (turns == 0 || properties.isEmpty()) return this;
        var next = new TreeMap<>(properties);
        String facing = properties.get("facing");
        if (facing != null && HORIZONTAL.contains(facing)) next.put("facing", turn(facing, turns));
        String axis = properties.get("axis");
        if (axis != null && turns % 2 == 1) next.put("axis", axis.equals("x") ? "z" : axis.equals("z") ? "x" : axis);
        String rotation = properties.get("rotation");
        if (rotation != null && rotation.matches("\\d+")) next.put("rotation", String.valueOf((Integer.parseInt(rotation) + 4 * turns) % 16));
        for (String side : HORIZONTAL) next.remove(side);
        for (String side : HORIZONTAL) {
            String value = properties.get(side);
            if (value != null) next.put(turn(side, turns), value);
        }
        return new BlockSpec(block, next);
    }

    static final java.util.List<String> HORIZONTAL = java.util.List.of("north", "east", "south", "west");

    static String turn(String side, int turns) {
        int i = HORIZONTAL.indexOf(side);
        return i < 0 ? side : HORIZONTAL.get((i + turns) % 4);
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) return block;
        var out = new StringBuilder(block).append('[');
        boolean first = true;
        for (var entry : properties.entrySet()) {
            if (!first) out.append(',');
            out.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return out.append(']').toString();
    }
}
