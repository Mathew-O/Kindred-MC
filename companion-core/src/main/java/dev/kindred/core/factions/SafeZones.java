package dev.kindred.core.factions;

import dev.kindred.core.construction.Blueprint;
import dev.kindred.core.construction.Generators;
import dev.kindred.core.construction.Pos;
import dev.kindred.core.construction.TradingHall;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.Ledger;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * In plain terms: the safe zones of a faction world: the spawn hub and the market district at (0, 0), where nobody
 * fights, nobody claims and nothing is captured, and only operators change a block; and the plan of the hub itself,
 * built from the builder's own generators.
 *
 * Technical: The safe-zone ledger and plan (M-06f). A {@code safe.zone} event names its chunks in {@code data.chunks}
 * (comma-separated keys) and its name as {@code subject}; a later event of the same name replaces it. The ledger's state
 * is every chunk of every zone. {@link #hub} is the spawn hub's chunks: every chunk with a block within
 * {@code safeRadius} of (0, 0) on the ground, in the overworld.
 */
public final class SafeZones implements Ledger<SafeZones.Book> {
    public static final String KIND = "safe.zone";
    public static final String HUB = "hub";
    public static final SafeZones INSTANCE = new SafeZones();

    /** Each zone's chunks, by name. */
    public static final class Book {
        public static final Book EMPTY = new Book(Map.of());

        private final Map<String, Set<String>> zones;
        /**
         * Every zone's chunks, gathered once: the book never changes, and the territory guard asks for them on every
         * block a player in a faction world may work (diag-factions303: 5.7% of the server thread's samples, the set
         * rebuilt on each question).
         */
        private final Set<String> chunks;

        public Book(Map<String, Set<String>> zones) {
            var copy = new java.util.TreeMap<String, Set<String>>();
            zones.forEach((name, chunks) -> copy.put(name, Set.copyOf(chunks)));
            this.zones = Collections.unmodifiableMap(copy);
            var all = new TreeSet<String>();
            copy.values().forEach(all::addAll);
            this.chunks = Collections.unmodifiableSet(all);
        }

        /** Each zone by name, and its chunks. */
        public Map<String, Set<String>> zones() { return zones; }

        /** Every chunk of every zone. */
        public Set<String> chunks() { return chunks; }

        @Override public boolean equals(Object other) { return other instanceof Book book && zones.equals(book.zones); }

        @Override public int hashCode() { return zones.hashCode(); }

        @Override public String toString() { return "Book[zones=" + zones + "]"; }
    }

    @Override public String name() { return "safe zones"; }

    @Override public Set<String> kinds() { return Set.of(KIND); }

    @Override public Book empty() { return Book.EMPTY; }

    @Override
    public Book apply(Book book, WorldEvent event) {
        if (event.subject().isEmpty()) return book;
        var next = new java.util.TreeMap<>(book.zones());
        String chunks = event.get("chunks", "");
        if (chunks.isEmpty()) next.remove(event.subject());
        else next.put(event.subject(), new TreeSet<>(List.of(chunks.split(","))));
        return new Book(next);
    }

    /** The spawn hub's chunks: every overworld chunk with a block within {@code radius} of (0, 0). */
    public static Set<String> hub(int radius) {
        var chunks = new TreeSet<String>();
        int reach = (radius >> 4) + 1;
        for (int cx = -reach; cx <= reach; cx++) for (int cz = -reach; cz <= reach; cz++) {
            var chunk = new Territory.Chunk(Territory.OVERWORLD, cx, cz);
            if (chunk.nearestToSpawn() <= radius) chunks.add(chunk.key());
        }
        return chunks;
    }

    /** The event that makes {@code chunks} the safe zone {@code name}, by {@code by} (an operator, or null for the console). */
    public static WorldEvent zone(String name, Set<String> chunks, java.util.UUID by, long tick, long realTimeMs) {
        return WorldEvent.recorded(tick, realTimeMs, Territory.OVERWORLD, KIND, by, name, "", Map.of("chunks", String.join(",", new TreeSet<>(chunks))));
    }

    /** One part of the hub's plan: a blueprint and where its origin goes, on the ground at (0, 0). */
    public record Part(String name, Blueprint blueprint, Pos origin) {}

    /**
     * The hub's plan from the builder's generators: a lighthouse at spawn to find it by, and the market district east of
     * it, a trading hall of twelve stalls in stone and spruce. Positions are relative to the ground at (0, 0).
     */
    public static List<Part> plan() {
        var parts = new ArrayList<Part>();
        parts.add(new Part("spawn lighthouse", Generators.lighthouse(16, "minecraft:white_concrete", "minecraft:red_concrete"), new Pos(-3, 0, -3)));
        parts.add(new Part("market district", TradingHall.of(TradingHall.MAX_CELLS, "spruce", "stone_bricks"), new Pos(12, 0, -8)));
        return List.copyOf(parts);
    }

    /** Whether {@code chunk} (a key) is in a safe zone. */
    public static boolean safe(Book book, String chunk) { return book.chunks().contains(chunk); }

    /** A chunk's key for a block position, as claims key it. */
    public static String chunkAt(String dimension, int x, int z) { return ClaimLedger.chunk(dimension, x >> 4, z >> 4); }
}
