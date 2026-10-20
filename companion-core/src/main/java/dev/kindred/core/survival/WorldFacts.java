package dev.kindred.core.survival;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * What the route planner may use besides the player's own items: the kinds of sources the player has actually
 * seen, with the distance to the nearest of each, and the methods the server side can carry out. The adapter
 * rebuilds this on every planning pass, so the plan follows the world as the player learns it.
 *
 * @param nearest blocks to the nearest observed source of each kind; a kind never seen is absent
 * @param methods ways of getting things the adapter can execute; the planner never picks one outside this set
 */
public record WorldFacts(Map<Source, Integer> nearest, Set<Method> methods) {
    /** Kinds of sources a route choice can depend on. */
    public enum Source { TREE, STONE, COAL_ORE, IRON_ORE, DIAMOND_ORE, LAVA, WATER, GRAVEL, CAVE }

    /** Alternative ways of reaching a sub-goal of the route. */
    public enum Method {
        /** Diamonds for a diamond pickaxe, then water poured onto lava and the obsidian mined: the classic route. */
        MINE_OBSIDIAN,
        /** Lava poured into the frame and hardened in place with water: two buckets and a lava source, no diamonds. */
        CAST_OBSIDIAN
    }

    public WorldFacts {
        Objects.requireNonNull(nearest);
        Objects.requireNonNull(methods);
        nearest = nearest.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(nearest));
        methods = methods.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(methods));
        for (int distance : nearest.values()) if (distance < 0) throw new IllegalArgumentException("Distances are not negative");
    }

    /** Nothing seen yet, and only the classic route available. */
    public static WorldFacts ordinary() {
        return new WorldFacts(Map.of(), Set.of(Method.MINE_OBSIDIAN));
    }

    public boolean can(Method method) { return methods.contains(method); }

    public boolean knows(Source source) { return nearest.containsKey(source); }

    public OptionalInt distance(Source source) {
        Integer distance = nearest.get(source);
        return distance == null ? OptionalInt.empty() : OptionalInt.of(distance);
    }
}
