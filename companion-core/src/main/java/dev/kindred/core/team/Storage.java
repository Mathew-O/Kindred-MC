package dev.kindred.core.team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a player has stored, as it knows it (E-43): each container it keeps things in, what it last counted there and
 * when, and whose it is, its own or its team's. With what it holds, this is its extended inventory, which its plans
 * and the value model count from. What it puts in and takes out, and what a teammate tells it it put in or took, change
 * the counts as they happen; a look inside sets them to what it sees, and what is short that nobody accounted for is
 * reported as gone. Pure: the adapter reports each look, each deposit and withdrawal, and each teammate's word.
 */
public final class Storage {
    /** Whose a container is: the player's own, or one its team shares. */
    public enum Whose { OWN, TEAM }

    /** A container, by its world and place. */
    public record Place(String world, int x, int y, int z) {
        public Place {
            Objects.requireNonNull(world);
        }

        /** "the chest at 12, 64, -30". */
        public String words() {
            return "the chest at " + x + ", " + y + ", " + z;
        }
    }

    /** One container as last known: whose, what was in it, and when it was last looked into or changed by a known hand. */
    public record Store(Place place, Whose whose, Map<String, Integer> counts, long seenAt) {
        public Store {
            Objects.requireNonNull(place);
            Objects.requireNonNull(whose);
            counts = Map.copyOf(counts);
        }

        public int count(String item) { return counts.getOrDefault(item, 0); }
    }

    /** What a look found against what was known: what is short, item by item, and what more is there. */
    public record Look(Place place, Whose whose, Map<String, Integer> gone, Map<String, Integer> more) {
        public Look {
            gone = Map.copyOf(gone);
            more = Map.copyOf(more);
        }

        /** Whether anything known to be there is not. */
        public boolean isShort() { return !gone.isEmpty(); }
    }

    private final Map<Place, Store> stores = new LinkedHashMap<>();

    /**
     * A look inside a container: what it holds now. The first look at one only notes it; after that, what is short of
     * what it knew is gone and what is more was put in by somebody, and the counts are what it saw.
     */
    public Look looked(Place place, Whose whose, Map<String, Integer> found, long now) {
        Objects.requireNonNull(found);
        Store known = stores.get(place);
        var gone = new LinkedHashMap<String, Integer>();
        var more = new LinkedHashMap<String, Integer>();
        if (known != null) {
            for (var entry : known.counts().entrySet()) {
                int left = found.getOrDefault(entry.getKey(), 0);
                if (left < entry.getValue()) gone.put(entry.getKey(), entry.getValue() - left);
            }
            for (var entry : found.entrySet()) {
                int had = known.count(entry.getKey());
                if (entry.getValue() > had) more.put(entry.getKey(), entry.getValue() - had);
            }
        }
        stores.put(place, new Store(place, known == null ? whose : known.whose(), positive(found), now));
        return new Look(place, known == null ? whose : known.whose(), gone, more);
    }

    /** Something put in, by the player itself or by a teammate who said so. */
    public void put(Place place, Whose whose, String item, int count, long now) {
        if (count <= 0) throw new IllegalArgumentException("Put in something");
        Store known = stores.get(place);
        var counts = new HashMap<>(known == null ? Map.<String, Integer>of() : known.counts());
        counts.merge(item, count, Integer::sum);
        stores.put(place, new Store(place, known == null ? whose : known.whose(), counts, now));
    }

    /** Something taken out, by the player itself or by a teammate who said so: no longer counted there. */
    public void took(Place place, String item, int count, long now) {
        if (count <= 0) throw new IllegalArgumentException("Take something");
        Store known = stores.get(place);
        if (known == null) return;
        var counts = new HashMap<>(known.counts());
        int left = known.count(item) - count;
        if (left > 0) counts.put(item, left); else counts.remove(item);
        stores.put(place, new Store(place, known.whose(), counts, now));
    }

    /** How many of an item it has stored, in all its containers. */
    public int stored(String item) {
        int total = 0;
        for (Store store : stores.values()) total += store.count(item);
        return total;
    }

    /** What it holds and what it has stored together, item by item: its extended inventory. */
    public Map<String, Integer> extended(Map<String, Integer> held) {
        var all = new HashMap<>(held);
        for (Store store : stores.values()) store.counts().forEach((item, count) -> all.merge(item, count, Integer::sum));
        return Map.copyOf(all);
    }

    public Optional<Store> store(Place place) {
        return Optional.ofNullable(stores.get(place));
    }

    /** How long since it last knew what was in a container, or none for one it does not know. */
    public Optional<Long> age(Place place, long now) {
        Store store = stores.get(place);
        return store == null ? Optional.empty() : Optional.of(now - store.seenAt());
    }

    /** Where it has a stack of an item stored, the fullest first. */
    public List<Store> holding(String item) {
        var found = new ArrayList<Store>();
        for (Store store : stores.values()) if (store.count(item) > 0) found.add(store);
        found.sort((a, b) -> Integer.compare(b.count(item), a.count(item)));
        return found;
    }

    private static Map<String, Integer> positive(Map<String, Integer> counts) {
        var kept = new HashMap<String, Integer>();
        counts.forEach((item, count) -> { if (count > 0) kept.put(item, count); });
        return kept;
    }
}
