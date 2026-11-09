package dev.kindred.core.survival;

import dev.kindred.core.construction.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In plain terms: a small wheat plot by a player's base, as players keep one early on: the soil tilled with a hoe,
 * sown with seeds, left to grow while the player gets on with other things, the ripe wheat harvested and the plot sown
 * again with the seeds that came with it, until it has the wheat it wants. It needs no water: wheat grows on dry
 * farmland, only more slowly, and farmland with a crop on it stays farmland.
 *
 * Technical: The wheat grown for a ranch (M-01a, the ranch's food earned in play). Pure: one pass at a time from what the
 * player sees, so it carries on from wherever the plot stands. The plot is {@code size} cells square from its
 * north-west corner, its soil at {@code y}. Ripe wheat is harvested before anything else; then dry soil tilled, bare
 * farmland sown; then, with the wheat it wants, done; with all sown and growing, it steps away ({@link Step.Growing}).
 */
public final class WheatPlot {
    /** A cell of the plot: soil to till, farmland bare, sown and growing, ripe, or something else there. */
    public enum Soil { DRY, FARMLAND, SOWN, RIPE, OTHER }

    /** What the player sees: its feet, each cell of the plot, and what it holds. */
    public record Observation(Pos feet, Map<Pos, Soil> soil, boolean hoe, int seeds, int wheat) { }

    public sealed interface Step {
        /** A hoe first: it has none. */
        record NeedHoe(String why) implements Step { }

        /** Seeds first: bare farmland and none in hand. */
        record NeedSeeds(String why) implements Step { }

        record Till(Pos at, String why) implements Step { }

        record Sow(Pos at, String why) implements Step { }

        record Harvest(Pos at, String why) implements Step { }

        /** All sown, none ripe: left to grow, to come back to. */
        record Growing(String why) implements Step { }

        record Done(String outcome) implements Step { }
    }

    private final Pos corner;
    private final int size;
    /** Planted from the food itself, as carrots and potatoes are: what is sown is what it wants to keep. */
    private final boolean seedIsFood;

    public WheatPlot(Pos corner, int size) {
        this(corner, size, false);
    }

    /**
     * With {@code seedIsFood}, a plot of a crop planted from the food itself (a pig's carrots or potatoes, M-01a): the
     * {@link Observation}'s seeds and wheat are then both what it holds of that one item. Once it holds what it wants it is
     * done; short of that, it plants one only when nothing is growing, so that each harvest (two to five from one) leaves
     * it more, and planting never eats the food it grew.
     */
    public WheatPlot(Pos corner, int size, boolean seedIsFood) {
        if (size < 1 || size > 9) throw new IllegalArgumentException("A plot 1 to 9 cells a side");
        this.corner = corner;
        this.size = size;
        this.seedIsFood = seedIsFood;
    }

    public Pos corner() { return corner; }

    /** The plot's cells, by their soil's position. */
    public List<Pos> cells() {
        var cells = new ArrayList<Pos>();
        for (int dz = 0; dz < size; dz++) for (int dx = 0; dx < size; dx++) cells.add(corner.add(dx, 0, dz));
        return cells;
    }

    /** The next step toward {@code wanted} wheat. */
    public Step next(Observation seen, int wanted) {
        var ripe = nearest(seen, Soil.RIPE);
        if (ripe.isPresent()) return new Step.Harvest(ripe.get(), seedIsFood ? "Harvest what is ripe" : "Harvest the ripe wheat");
        var dry = nearest(seen, Soil.DRY);
        if (dry.isPresent()) {
            if (!seen.hoe()) return new Step.NeedHoe("A hoe to till the plot");
            return new Step.Till(dry.get(), "Till the plot");
        }
        var bare = nearest(seen, Soil.FARMLAND);
        if (seedIsFood) {
            boolean sown = seen.soil().values().stream().anyMatch(s -> s == Soil.SOWN);
            if (seen.wheat() >= wanted) return new Step.Done("Enough to keep: " + seen.wheat());
            if (bare.isPresent() && seen.seeds() > 0 && !sown) return new Step.Sow(bare.get(), "Plant the plot");
            if (sown) return new Step.Growing("Planted; waiting for it to ripen");
            if (bare.isPresent()) return new Step.NeedSeeds("One to plant");
            return new Step.Done("The plot has nothing to grow on");
        }
        if (bare.isPresent()) {
            if (seen.seeds() <= 0) return new Step.NeedSeeds("Seeds to sow the plot");
            return new Step.Sow(bare.get(), "Sow the plot");
        }
        if (seen.wheat() >= wanted) return new Step.Done("Wheat enough: " + seen.wheat());
        if (seen.soil().values().stream().noneMatch(s -> s == Soil.SOWN)) return new Step.Done("The plot has nothing to grow on");
        return new Step.Growing("The wheat is sown; waiting for it to ripen");
    }

    private static Optional<Pos> nearest(Observation seen, Soil kind) {
        return seen.soil().entrySet().stream().filter(e -> e.getValue() == kind).map(Map.Entry::getKey)
                .min(Comparator.<Pos>comparingLong(p -> p.distanceSquared(seen.feet())).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
    }
}
