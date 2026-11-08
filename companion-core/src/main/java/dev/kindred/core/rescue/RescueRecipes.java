package dev.kindred.core.rescue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: the ways out that have worked, each kept with the kind of hole it worked in, so the next player in the
 * same kind of hole tries it first without asking anyone. One that works gains weight; one that fails twice running is
 * dropped. At most five hundred are kept, shared by every player on the server.
 *
 * Technical: F-29d. A bounded, ordered book of recipes keyed by {@link RescueSignature#key}. Pure and confined to the
 * caller's thread (the server's); the adapter reads and writes its file. A recipe's steps are written as the vocabulary
 * writes them and parsed and gated like any plan before they run.
 */
public final class RescueRecipes {
    /** The most recipes kept. */
    public static final int MAX = 500;
    /** Failures in a row that retire a recipe. */
    public static final int RETIRE_AFTER = 2;

    /**
     * A recipe: the signature it was learned in, the steps that ran (as far as each went), how often it has worked, its
     * failures in a row, and where it came from ({@code server}, or an evaluation run and seed for a shipped one).
     */
    public record Recipe(String signature, List<String> steps, int weight, int failures, String source) {
        public Recipe {
            Objects.requireNonNull(signature);
            steps = List.copyOf(steps);
            Objects.requireNonNull(source);
            if (steps.isEmpty()) throw new IllegalArgumentException("A recipe has a step at least");
        }
    }

    private final List<Recipe> recipes = new ArrayList<>();
    private long revision;

    /** A book of these recipes, in order; beyond the bound the last are left out. */
    public RescueRecipes(List<Recipe> initial) {
        for (Recipe recipe : initial) {
            if (recipes.size() >= MAX) break;
            if (RescuePlan.parse(recipe.steps(), "").plan().isEmpty()) continue; // Not of this vocabulary: left out.
            recipes.add(recipe);
        }
    }

    public RescueRecipes() {
        this(List.of());
    }

    /** The recipes, in order. */
    public List<Recipe> all() { return List.copyOf(recipes); }

    /** Changes so far: the adapter writes the file when this moves. */
    public long revision() { return revision; }

    /** The recipe to try first for a scene: the heaviest of those learned in the same kind of scene. */
    public Optional<Recipe> match(RescueSignature signature) {
        String key = signature.key();
        return recipes.stream().filter(r -> r.signature().equals(key)).max(Comparator.comparingInt(Recipe::weight));
    }

    /**
     * A spell got out by {@code steps} in a scene of this signature: the same recipe gains weight and its failures are
     * forgiven; a new one is kept, the lightest (the oldest of those) giving way when the book is full.
     */
    public Recipe learned(RescueSignature signature, List<String> steps, String source) {
        String key = signature.key();
        for (int i = 0; i < recipes.size(); i++) {
            Recipe r = recipes.get(i);
            if (r.signature().equals(key) && r.steps().equals(steps)) {
                Recipe heavier = new Recipe(key, steps, r.weight() + 1, 0, r.source());
                recipes.set(i, heavier);
                revision++;
                return heavier;
            }
        }
        if (recipes.size() >= MAX) {
            int lightest = 0;
            for (int i = 1; i < recipes.size(); i++) if (recipes.get(i).weight() < recipes.get(lightest).weight()) lightest = i;
            recipes.remove(lightest);
        }
        Recipe fresh = new Recipe(key, steps, 1, 0, source);
        recipes.add(fresh);
        revision++;
        return fresh;
    }

    /** A recipe tried first that got the player out: it gains weight. */
    public void worked(Recipe recipe) {
        int i = recipes.indexOf(find(recipe));
        if (i < 0) return;
        Recipe r = recipes.get(i);
        recipes.set(i, new Recipe(r.signature(), r.steps(), r.weight() + 1, 0, r.source()));
        revision++;
    }

    /** A recipe tried first that did not: a failure counted, and at {@link #RETIRE_AFTER} in a row it is dropped. */
    public void failed(Recipe recipe) {
        Recipe r = find(recipe);
        int i = recipes.indexOf(r);
        if (i < 0) return;
        if (r.failures() + 1 >= RETIRE_AFTER) recipes.remove(i);
        else recipes.set(i, new Recipe(r.signature(), r.steps(), r.weight(), r.failures() + 1, r.source()));
        revision++;
    }

    /** The book's own copy of a recipe: by signature and steps, its weight and failures as they now are. */
    private Recipe find(Recipe recipe) {
        for (Recipe r : recipes) if (r.signature().equals(recipe.signature()) && r.steps().equals(recipe.steps())) return r;
        return null;
    }
}
