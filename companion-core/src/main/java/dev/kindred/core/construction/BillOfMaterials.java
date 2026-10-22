package dev.kindred.core.construction;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The items a build takes (E-08a): one a block, from the item it is placed from, less what the world already holds in
 * place, and what the player holds against it. Blocks whose placement is not modelled yet are listed apart, not
 * counted. Pure.
 */
public record BillOfMaterials(Map<String, Integer> needed, Map<String, Integer> missing, List<String> unsupported) {
    public BillOfMaterials {
        needed = Collections.unmodifiableMap(new TreeMap<>(needed));
        missing = Collections.unmodifiableMap(new TreeMap<>(missing));
        unsupported = List.copyOf(unsupported);
    }

    /** The bill for what is still to place of {@code blocks} in {@code site}, against {@code held} item counts. */
    public static BillOfMaterials of(List<Blueprint.Block> blocks, BuildSite site, Map<String, Integer> held) {
        var needed = new TreeMap<String, Integer>();
        var unsupported = new java.util.TreeSet<String>();
        for (var b : blocks) {
            if (site.cell(b.at()).map(c -> c.state().sameAs(b.spec())).orElse(false)) continue;
            if (Placement.rule(b.spec()) == Placement.Rule.UNSUPPORTED) {
                unsupported.add(b.spec().block());
                continue;
            }
            needed.merge(Placement.item(b.spec()), 1, Integer::sum);
        }
        var missing = new TreeMap<String, Integer>();
        needed.forEach((item, count) -> {
            int short_ = count - held.getOrDefault(item, 0);
            if (short_ > 0) missing.put(item, short_);
        });
        return new BillOfMaterials(needed, missing, List.copyOf(unsupported));
    }

    public boolean complete() {
        return missing.isEmpty();
    }
}
