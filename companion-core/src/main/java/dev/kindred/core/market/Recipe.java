package dev.kindred.core.market;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * In plain terms: one way of making something: what it makes and how many, and each thing it takes (any one of a few
 * alternatives, as any planks make sticks).
 *
 * Technical: A recipe as the market sees it (M-06g): crafting, smelting, blasting, smoking, campfire cooking,
 * stonecutting and smithing alike. The adapter reads them from the server's own recipes; the arbitrage test from the
 * game's recipe files. Fuel is not counted: leaving it out only makes a made thing look cheaper, never dearer.
 */
public record Recipe(String id, String output, int count, List<Set<String>> ingredients) {
    public Recipe {
        Objects.requireNonNull(id);
        Objects.requireNonNull(output);
        if (count < 1) throw new IllegalArgumentException("A recipe makes something");
        ingredients = ingredients.stream().filter(set -> !set.isEmpty()).map(Set::copyOf).toList();
        if (ingredients.isEmpty()) throw new IllegalArgumentException("A recipe takes something");
    }
}
