package dev.kindred.core.atlas;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In plain terms: where each thing a player looks for is likely to be by the game's own rules (diamonds near the bottom
 * of the world, villages on plains), so that a player who has not seen one yet looks where it is likely.
 *
 * Technical: F-30. The atlas's priors, read from {@code priors.txt} beside this class, data rather than code: for each
 * resource the bands of height it is found in, with their weights, the biomes, or that it is found by eyes. The numbers
 * are Minecraft 1.21.1's, each read from the Minecraft Wiki (the file says which pages); none is used before it is there.
 */
public final class Priors {
    /**
     * A band of heights in a dimension, weighted against the resource's other bands: evenly, or, with a peak, as a
     * triangle, full at the peak and nothing at its ends, as the game spreads iron and coal.
     */
    public record Band(String dimension, int from, int to, double weight, Integer peak) {
        boolean holds(String dim, int y) { return dimension.equals(dim) && y >= from && y <= to; }

        double at(int y) {
            if (peak == null) return weight;
            double side = y <= peak ? (double) (y - from) / Math.max(1, peak - from) : (double) (to - y) / Math.max(1, to - peak);
            return weight * Math.max(0, Math.min(1, side));
        }
    }

    private final Map<Atlas.Resource, List<Band>> bands = new EnumMap<>(Atlas.Resource.class);
    private final Map<Atlas.Resource, Set<String>> biomes = new EnumMap<>(Atlas.Resource.class);
    private final Map<Atlas.Resource, String> dimensions = new EnumMap<>(Atlas.Resource.class);
    private final Set<Atlas.Resource> byEyes = new HashSet<>();

    private Priors() { }

    /** The priors of the resource file that comes with this class. */
    public static Priors standard() {
        var stream = Priors.class.getResourceAsStream("priors.txt");
        if (stream == null) throw new IllegalStateException("priors.txt is missing beside " + Priors.class.getName());
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return parse(reader.lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The priors these lines give: one rule a line, {@code #} for a comment; a line it cannot read is refused. */
    public static Priors parse(List<String> lines) {
        var priors = new Priors();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\s+");
            if (f.length < 3) throw new IllegalArgumentException("Not a prior: " + line);
            Atlas.Resource resource = Atlas.Resource.valueOf(f[0].toUpperCase(Locale.ROOT));
            String dimension = f[1];
            priors.dimensions.putIfAbsent(resource, dimension);
            switch (f[2]) {
                case "y" -> {
                    if (f.length != 6 && f.length != 7) throw new IllegalArgumentException("A band is: resource dimension y from to weight [peak]: " + line);
                    int from = Integer.parseInt(f[3]), to = Integer.parseInt(f[4]);
                    double weight = Double.parseDouble(f[5]);
                    Integer peak = f.length == 7 ? Integer.valueOf(f[6]) : null;
                    if (from > to || weight <= 0 || peak != null && (peak < from || peak > to)) throw new IllegalArgumentException("Not a band: " + line);
                    priors.bands.computeIfAbsent(resource, r -> new ArrayList<>()).add(new Band(dimension, from, to, weight, peak));
                }
                case "biome" -> {
                    if (f.length != 4) throw new IllegalArgumentException("A biome is: resource dimension biome id: " + line);
                    priors.biomes.computeIfAbsent(resource, r -> new HashSet<>()).add(f[3]);
                }
                case "eyes" -> priors.byEyes.add(resource);
                default -> throw new IllegalArgumentException("Unknown prior '" + f[2] + "': " + line);
            }
        }
        return priors;
    }

    /** The dimension a resource is found in, by its first rule, or the Overworld. */
    public String dimension(Atlas.Resource resource) { return dimensions.getOrDefault(resource, "overworld"); }

    /** Found by the eyes' flights, not by looking. */
    public boolean byEyes(Atlas.Resource resource) { return byEyes.contains(resource); }

    /** The weight of the heights at {@code y} for the resource: the sum of its bands that hold it, or 0. */
    public double atHeight(Atlas.Resource resource, String dimension, int y) {
        double weight = 0;
        for (Band band : bands.getOrDefault(resource, List.of())) if (band.holds(dimension, y)) weight += band.at(y);
        return weight;
    }

    /** The height in the dimension where its bands weigh most, the middle of the heaviest run; empty when it has none. */
    public java.util.OptionalInt bestHeight(Atlas.Resource resource, String dimension) {
        var own = bands.getOrDefault(resource, List.of()).stream().filter(b -> b.dimension().equals(dimension)).toList();
        if (own.isEmpty()) return java.util.OptionalInt.empty();
        int low = own.stream().mapToInt(Band::from).min().orElseThrow(), high = own.stream().mapToInt(Band::to).max().orElseThrow();
        double best = -1;
        int start = low, end = low;
        for (int y = low; y <= high; y++) {
            double w = atHeight(resource, dimension, y);
            if (w > best + 1e-9) { best = w; start = y; end = y; }
            else if (Math.abs(w - best) <= 1e-9 && end == y - 1) end = y;
        }
        return java.util.OptionalInt.of((start + end) / 2);
    }

    /** Whether the biome (an id such as {@code minecraft:plains}) is one the resource is found in; true if it names none. */
    public boolean inBiome(Atlas.Resource resource, String biome) {
        var own = biomes.get(resource);
        return own == null || own.contains("any") || biome != null && own.contains(biome);
    }

    /** Whether the resource names biomes it is found in, so a chunk's biome tells for or against it. */
    public boolean byBiome(Atlas.Resource resource) {
        var own = biomes.get(resource);
        return own != null && !own.contains("any");
    }
}
