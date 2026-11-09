package dev.kindred.core.market;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * In plain terms: what the market may know of a stack offered for sale: what it is, how many, how worn, its
 * enchantments, its potion, whether it holds other items, and whether it carries anything else the market does not
 * understand.
 *
 * Technical: The adapter's description of an item stack (M-06g), immutable and Minecraft-free. {@code unknown} names the
 * data components on the stack the canonicaliser has no rule for; any one of them makes the stack valuation pending.
 * A custom name is never in it: a renamed item is valued as the same item unnamed.
 */
public record ItemFacts(String id, int count, int damage, int maxDamage, Map<String, Integer> enchantments, String potion,
                        boolean holdsItems, Set<String> unknown) {
    public ItemFacts {
        Objects.requireNonNull(id);
        if (count < 1 || damage < 0 || maxDamage < 0 || damage > maxDamage) throw new IllegalArgumentException("Invalid stack facts");
        enchantments = java.util.Collections.unmodifiableMap(new TreeMap<>(enchantments));
        potion = potion == null ? "" : potion;
        unknown = Set.copyOf(unknown);
    }

    /** A plain stack: no wear, enchantment, potion, contents or other data. */
    public static ItemFacts plain(String id, int count) { return new ItemFacts(id, count, 0, 0, Map.of(), "", false, Set.of()); }
}
