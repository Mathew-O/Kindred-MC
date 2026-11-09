package dev.kindred.core.market;

import java.util.Map;
import java.util.StringJoiner;

/**
 * In plain terms: what the market calls a stack: the item, how worn (in quarters), its potion and the enchantments that
 * carry value; or why it will not pay for it: an admin-only item, a container with things in it, data it does not know,
 * or an item with no price yet ("valuation pending").
 *
 * Technical: The canonicaliser (M-06g). A renamed item is keyed as the item; a player head, a filled map, a written
 * book, a banner's patterns, a trim, a firework's stars and a suspicious stew's effect are keyed as their bare item,
 * since none of that data is valued: nothing a player writes onto an item can raise its price. Wear is kept in quarters
 * of durability left (a stack below a quarter is keyed at a tenth).
 */
public final class Canonical {
    private Canonical() {}

    /** A stack's market key and what it is valued as, or why it is refused. */
    public record Key(String key, String item, double wear, Map<String, Integer> enchantments, String potion, String refusal) {
        public boolean accepted() { return refusal == null; }

        static Key refused(String why) { return new Key(null, null, 0, Map.of(), "", why); }
    }

    public static Key of(ItemFacts facts, MarketTable table) {
        String id = MarketTable.id(facts.id());
        if (table.admin().contains(id) || id.endsWith("_spawn_egg")) return Key.refused("Never paid for: an admin-only item");
        if (facts.holdsItems()) return Key.refused("A container with things in it: empty it first");
        if (!facts.unknown().isEmpty()) return Key.refused("Valuation pending: data the market does not know (" + facts.unknown().iterator().next() + ")");
        for (String enchantment : facts.enchantments().keySet()) {
            if (!table.enchantments().containsKey(MarketTable.id(enchantment))) return Key.refused("Valuation pending: enchantment " + enchantment);
        }
        double wear = 1;
        if (facts.maxDamage() > 0 && facts.damage() > 0) {
            double left = (double) (facts.maxDamage() - facts.damage()) / facts.maxDamage();
            wear = left >= 0.75 ? 0.75 : left >= 0.5 ? 0.5 : left >= 0.25 ? 0.25 : 0.1;
        }
        var key = new StringBuilder(id);
        if (!facts.potion().isEmpty()) key.append('#').append(facts.potion());
        if (wear < 1) key.append('@').append((int) Math.round(wear * 100));
        if (!facts.enchantments().isEmpty()) {
            var joined = new StringJoiner(",", "{", "}");
            facts.enchantments().forEach((e, level) -> joined.add(MarketTable.id(e) + "=" + level));
            key.append(joined);
        }
        String item = facts.potion().isEmpty() ? id : id + "#" + facts.potion();
        return new Key(key.toString(), item, wear, facts.enchantments(), facts.potion(), null);
    }
}
