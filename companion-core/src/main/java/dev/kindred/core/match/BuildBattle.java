package dev.kindred.core.match;

import dev.kindred.core.VanillaIds;
import dev.kindred.core.construction.Woods;

import dev.kindred.core.construction.Blueprint;
import dev.kindred.core.construction.Generators;
import dev.kindred.core.construction.Pos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: one round of a build battle: the theme, each builder's own plot, and the scores of one to
 * five that every builder gives every other build, humans and AI players alike. An AI builder makes its
 * design from the mod's own building plans, and scores others' builds by their size, variety and height.
 *
 * Technical: A build battle's round (M-04a): the theme, each builder's plot, and the votes. Every builder
 * builds on its own plot in the build's time, then each votes on every other build, one to five, the same for
 * humans and AIs. The best mean wins. An AI builder turns the theme into a design from Kindred's own
 * generators, sized and coloured by chance, and votes as the {@link #judge judge} scores what it sees on a
 * plot. Pure.
 */
public final class BuildBattle {
    /** A side of a plot, and the gap between plots. */
    public static final int PLOT = 15, GAP = 3;

    /** What a round is built on, and how an AI builder makes a design for it. */
    public enum Theme {
        COTTAGE("A cottage"), WATCHTOWER("A watchtower"), PYRAMID("A pyramid"), LIGHTHOUSE("A lighthouse");

        private final String title;

        Theme(String title) { this.title = title; }

        public String title() { return title; }
    }

    /** One builder's square of ground: its north-west corner on the floor, {@link #PLOT} a side. */
    public record Plot(UUID builder, Pos origin) {
        /** Whether a spot is on this plot, above its floor. */
        public boolean contains(Pos at) {
            return at.x() >= origin.x() && at.x() < origin.x() + PLOT && at.z() >= origin.z() && at.z() < origin.z() + PLOT && at.y() > origin.y();
        }

        /** Where a design built on this plot goes: in from its edge, on its floor. */
        public Pos site(Blueprint design) {
            int width = 0, depth = 0;
            for (var b : design.blocks()) { width = Math.max(width, b.at().x() + 1); depth = Math.max(depth, b.at().z() + 1); }
            return origin.add((PLOT - width) / 2, 1, (PLOT - depth) / 2);
        }
    }

    private final Theme theme;
    private final List<Plot> plots;
    private final Map<UUID, Map<UUID, Integer>> votes = new LinkedHashMap<>();

    /** A round on a theme with its builders' plots, no votes cast yet. */
    public BuildBattle(Theme theme, List<Plot> plots) {
        this.theme = theme;
        this.plots = List.copyOf(plots);
    }

    public Theme theme() { return theme; }

    public List<Plot> plots() { return plots; }

    public Optional<Plot> plot(UUID builder) { return plots.stream().filter(p -> p.builder().equals(builder)).findFirst(); }

    /** Plots for {@code builders} in a row eastward from {@code start}, {@link #GAP} apart. */
    public static List<Plot> layout(List<UUID> builders, Pos start) {
        var plots = new ArrayList<Plot>();
        for (int i = 0; i < builders.size(); i++) plots.add(new Plot(builders.get(i), start.add(i * (PLOT + GAP), 0, 0)));
        return plots;
    }

    /** A theme by chance. */
    public static Theme pick(Random random) {
        return Theme.values()[random.nextInt(Theme.values().length)];
    }

    /** {@code voter} scores {@code builder}'s build, one to five; a later vote replaces an earlier. Why not, if refused. */
    public Optional<String> vote(UUID voter, UUID builder, int score) {
        if (plot(voter).isEmpty()) return Optional.of("Only a builder in this round votes");
        if (plot(builder).isEmpty()) return Optional.of("No build of theirs in this round");
        if (voter.equals(builder)) return Optional.of("Nobody votes on its own build");
        if (score < 1 || score > 5) return Optional.of("A vote is one to five");
        votes.computeIfAbsent(builder, b -> new LinkedHashMap<>()).put(voter, score);
        return Optional.empty();
    }

    /** Each builder's mean score; a build nobody voted on has none. */
    public Map<UUID, Double> scores() {
        var out = new LinkedHashMap<UUID, Double>();
        for (var plot : plots) {
            var got = votes.getOrDefault(plot.builder(), Map.of());
            if (!got.isEmpty()) out.put(plot.builder(), got.values().stream().mapToInt(Integer::intValue).average().orElse(0));
        }
        return out;
    }

    /** The builders with the best mean, tied ones together; nobody if nobody voted. */
    public Set<UUID> winners() {
        var scores = scores();
        double best = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(-1);
        var out = new LinkedHashSet<UUID>();
        for (var e : scores.entrySet()) if (Math.abs(e.getValue() - best) < 1e-9) out.add(e.getKey());
        return out;
    }

    /**
     * Whether a schematic from the server's library may serve as a design for {@code theme} (M-04, M-05): its name names
     * the theme, and it fits a plot with a block to walk round it on each side and is no taller than sixteen.
     */
    public static boolean fitsTheme(String schematic, Blueprint design, Theme theme) {
        if (schematic == null || !schematic.toLowerCase(java.util.Locale.ROOT).contains(theme.name().toLowerCase(java.util.Locale.ROOT))) return false;
        int width = 0, depth = 0, height = 0;
        for (var b : design.blocks()) {
            if (b.at().x() < 0 || b.at().y() < 0 || b.at().z() < 0) return false;
            width = Math.max(width, b.at().x() + 1);
            depth = Math.max(depth, b.at().z() + 1);
            height = Math.max(height, b.at().y() + 1);
        }
        return !design.blocks().isEmpty() && width <= PLOT - 2 && depth <= PLOT - 2 && height <= 16;
    }

    /** Whether {@code voter} has voted on {@code builder}'s build. */
    public boolean voted(UUID voter, UUID builder) {
        return votes.getOrDefault(builder, Map.of()).containsKey(voter);
    }

    /** Whether {@code voter} has voted on every other build. */
    public boolean votedAll(UUID voter) {
        for (var plot : plots) {
            if (plot.builder().equals(voter)) continue;
            if (!votes.getOrDefault(plot.builder(), Map.of()).containsKey(voter)) return false;
        }
        return true;
    }

    /**
     * An AI builder's design for {@code theme}: one of Kindred's own generators, its size and materials by chance, every
     * one fitting a plot with room to walk round it.
     */
    public static Blueprint design(Theme theme, Random random) {
        return switch (theme) {
            case COTTAGE -> {
                var hut = Generators.hut(5 + 2 * random.nextInt(2), 5 + 2 * random.nextInt(2), 3);
                String wood = pickOf(random, "oak", "spruce", "birch", "dark_oak");
                String stone = pickOf(random, VanillaIds.COBBLESTONE, VanillaIds.ICON_STONE, VanillaIds.BRICKS, VanillaIds.MOSSY_COBBLESTONE);
                yield hut.recolored(Map.of(VanillaIds.OAK_PLANKS, Woods.planks(wood), VanillaIds.ICON_WOOD, Woods.log(wood),
                        VanillaIds.OAK_STAIRS, Woods.stairs(wood), VanillaIds.OAK_SLAB, Woods.slab(wood),
                        VanillaIds.COBBLESTONE, stone), "cottage of " + wood);
            }
            case WATCHTOWER -> Generators.tower(5 + 2 * random.nextInt(2), 7 + random.nextInt(4),
                    pickOf(random, VanillaIds.ICON_STONE, VanillaIds.COBBLESTONE, VanillaIds.DEEPSLATE_BRICKS, VanillaIds.POLISHED_ANDESITE));
            case PYRAMID -> Generators.pyramid(7 + 2 * random.nextInt(3), pickOf(random, VanillaIds.SANDSTONE, VanillaIds.RED_SANDSTONE, VanillaIds.SMOOTH_SANDSTONE));
            case LIGHTHOUSE -> {
                String band = pickOf(random, VanillaIds.RED_CONCRETE, VanillaIds.BLUE_CONCRETE, VanillaIds.BLACK_CONCRETE);
                yield Generators.lighthouse(8 + random.nextInt(4), VanillaIds.WHITE_CONCRETE, band);
            }
        };
    }

    private static String pickOf(Random random, String... options) { return options[random.nextInt(options.length)]; }

    /**
     * How an AI player scores a build, one to five, from the blocks it can see standing on a plot: its size, the kinds
     * of block in it, its height, and for the tall themes, height again. A plot left bare is a one.
     *
     * @param blocks the block kinds on the plot above its floor, by place
     */
    public static int judge(Map<Pos, String> blocks, Theme theme) {
        if (blocks.isEmpty()) return 1;
        int n = blocks.size();
        var kinds = new HashSet<>(blocks.values());
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (Pos at : blocks.keySet()) { low = Math.min(low, at.y()); high = Math.max(high, at.y()); }
        int height = high - low + 1;
        double score = n >= 200 ? 3 : n >= 100 ? 2.5 : n >= 50 ? 2 : n >= 15 ? 1.5 : 1;
        score += kinds.size() >= 5 ? 1 : kinds.size() >= 3 ? 0.5 : 0;
        boolean tall = theme == Theme.WATCHTOWER || theme == Theme.LIGHTHOUSE;
        score += tall ? (height >= 10 ? 1 : height >= 6 ? 0.5 : 0) : (height >= 5 ? 0.5 : 0);
        return (int) Math.max(1, Math.min(5, Math.round(score)));
    }
}
