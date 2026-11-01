package dev.kindred.core.atlas;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: one player's map of one world, from its own play. Where it has been and what it has looked into; what
 * it saw where (iron here, diamonds there, a fortress's bricks); where it was hurt or killed, by day or by night, a
 * memory that fades over three game days; and where it can shelter. With the game's own odds of where things are, it
 * says where to look for something it has not seen yet, and how dangerous a place has been.
 *
 * Technical: F-30. Pure and per world, fed by the adapter with only what the player could see (WorldKnowledge's exposure
 * and line-of-sight rules stand) and what happened to it. Chunks are kept by dimension and chunk coordinates: visited
 * (stood in, with the biome the adapter reported) or seen into (a sighting there). Bounded to {@link #MAX_CHUNKS}: past
 * it, the chunk longest unseen that it never stood in goes first, then the one longest unseen. Danger is kept by
 * 16-block cell, each hurt weighing one and each death three, tagged by day or night and fading to nothing over
 * {@link #FADE}. Queries: {@link #whereToLookFor}, {@link #nearestFrontier}, {@link #dangerAt}, {@link #safeShelterNear}.
 * Mutated on one thread; {@link #snapshot} is a plain copy for saving.
 */
public final class Atlas {
    public static final int MAX_CHUNKS = 4096, MAX_CELLS = 1024, MAX_HARMS = 16, MAX_SHELTERS = 64;
    /** A hurt or a death weighs nothing after three game days. */
    public static final long FADE = 3 * 24_000L;
    /** A cell of the danger map: sixteen blocks a side, as a chunk, and sixteen high. */
    public static final int CELL = 16;
    /** A death weighs this many hurts. */
    public static final int DEATH = 3;
    /** A hurt by day read at night, or one by night read by day, counts this much. */
    public static final double OTHER_TIME = 0.25;

    /** What a player looks for. */
    public enum Resource { DIAMOND, IRON, COAL, LAVA, WATER, TREES, VILLAGE, FORTRESS, STRONGHOLD }

    /** A block in a dimension ({@code overworld}, {@code the_nether}, {@code the_end}). */
    public record Place(String dimension, int x, int y, int z) {
        public Place {
            Objects.requireNonNull(dimension);
        }

        ChunkKey chunk() { return new ChunkKey(dimension, Math.floorDiv(x, 16), Math.floorDiv(z, 16)); }

        CellKey cell() { return new CellKey(dimension, Math.floorDiv(x, CELL), Math.floorDiv(y, CELL), Math.floorDiv(z, CELL)); }

        double flatDistance(Place other) { return Math.hypot(x - other.x, z - other.z); }
    }

    /** A chunk by its coordinates in a dimension. */
    public record ChunkKey(String dimension, int x, int z) {
        Place centre(int y) { return new Place(dimension, x * 16 + 8, y, z * 16 + 8); }
    }

    /** A cell of the danger map. */
    public record CellKey(String dimension, int x, int y, int z) { }

    /** A sighting: how many times, the latest place, when, and whose (null for its own; a teammate's name else). */
    public record Sighting(Resource resource, Place at, int count, long last, String by) { }

    /** Where to look: a place it saw the thing at, or a chunk on the edge of what it knows where the thing is likely. */
    public record Lead(Place at, boolean seen, String why) { }

    /** A place to shelter: a bed, a base, a sealed hole. */
    public record Shelter(Place at, String kind) { }

    /** A hurt or a death in a cell: when, by night, and its weight. */
    public record Harm(long time, boolean night, int weight) { }

    private static final class Chunk {
        long last;
        boolean visited;
        String biome;
        final Map<Resource, Sighting> sightings = new EnumMap<>(Resource.class);
    }

    private final Priors priors;
    private final Map<ChunkKey, Chunk> chunks = new LinkedHashMap<>();
    private final Map<CellKey, List<Harm>> danger = new HashMap<>();
    private final List<Shelter> shelters = new ArrayList<>();

    public Atlas(Priors priors) {
        this.priors = Objects.requireNonNull(priors);
    }

    public Priors priors() { return priors; }

    // ------------------------------------------------------------------ fed

    /** It stood in this place's chunk at {@code time}, the chunk's biome as the adapter reports it. */
    public void visit(Place at, String biome, long time) {
        Chunk chunk = chunk(at.chunk());
        chunk.visited = true;
        chunk.last = Math.max(chunk.last, time);
        if (biome != null) chunk.biome = biome;
    }

    /** It saw {@code resource} at a place: that place's chunk is seen into, and the sighting kept, the latest place. */
    public void sighted(Resource resource, Place at, long time, String by) {
        Chunk chunk = chunk(at.chunk());
        chunk.last = Math.max(chunk.last, time);
        var before = chunk.sightings.get(resource);
        chunk.sightings.put(resource, new Sighting(resource, at, before == null ? 1 : before.count() + 1, time, by));
    }

    /** It looked where it saw {@code resource} in that chunk and none is there now. */
    public void gone(Resource resource, Place at) {
        Chunk chunk = chunks.get(at.chunk());
        if (chunk != null) chunk.sightings.remove(resource);
    }

    /** It was hurt (weight 1) or killed ({@link #DEATH}) at a place, by night or by day. */
    public void harmed(Place at, boolean night, long time, int weight) {
        CellKey key = at.cell();
        if (!danger.containsKey(key) && danger.size() >= MAX_CELLS) {
            CellKey oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (var entry : danger.entrySet()) {
                long latest = entry.getValue().stream().mapToLong(Harm::time).max().orElse(Long.MIN_VALUE);
                if (latest < oldestTime) { oldestTime = latest; oldest = entry.getKey(); }
            }
            danger.remove(oldest);
        }
        var harms = danger.computeIfAbsent(key, k -> new ArrayList<>());
        harms.add(new Harm(time, night, weight));
        if (harms.size() > MAX_HARMS) harms.removeFirst();
    }

    /** A place it can shelter at; the same place again replaces it. */
    public void shelter(Place at, String kind) {
        shelters.removeIf(s -> s.at().equals(at));
        shelters.add(new Shelter(at, kind));
        if (shelters.size() > MAX_SHELTERS) shelters.removeFirst();
    }

    private Chunk chunk(ChunkKey key) {
        Chunk chunk = chunks.get(key);
        if (chunk != null) return chunk;
        if (chunks.size() >= MAX_CHUNKS) evict();
        chunk = new Chunk();
        chunks.put(key, chunk);
        return chunk;
    }

    /** The chunk it never stood in that it saw longest ago, or, with none, the one longest unseen. */
    private void evict() {
        ChunkKey drop = null;
        long dropTime = Long.MAX_VALUE;
        boolean dropVisited = true;
        for (var entry : chunks.entrySet()) {
            var chunk = entry.getValue();
            boolean better = dropVisited && !chunk.visited || dropVisited == chunk.visited && chunk.last < dropTime;
            if (drop == null || better) {
                drop = entry.getKey();
                dropTime = chunk.last;
                dropVisited = chunk.visited;
            }
        }
        chunks.remove(drop);
    }

    // ------------------------------------------------------------------ asked

    /** How many chunks it has stood in or seen into. */
    public int explored() { return chunks.size(); }

    /** Its latest sighting of {@code resource}, anywhere. */
    public Optional<Sighting> lastSeen(Resource resource) {
        Sighting latest = null;
        for (var chunk : chunks.values()) {
            var s = chunk.sightings.get(resource);
            if (s != null && (latest == null || s.last() > latest.last())) latest = s;
        }
        return Optional.ofNullable(latest);
    }

    /**
     * Where to look for {@code resource} from {@code from}: the nearest place in its dimension it saw one, else the
     * frontier chunk {@link #nearestFrontier} ranks first for it, at the height the priors weigh most. None in another
     * dimension than the resource's, nor for what eyes find.
     */
    public Optional<Lead> whereToLookFor(Resource resource, Place from) {
        Sighting nearest = null;
        for (var chunk : chunks.values()) {
            var s = chunk.sightings.get(resource);
            if (s == null || !s.at().dimension().equals(from.dimension())) continue;
            if (nearest == null || s.at().flatDistance(from) < nearest.at().flatDistance(from)) nearest = s;
        }
        if (nearest != null) {
            return Optional.of(new Lead(nearest.at(), true, "saw " + name(resource) + (nearest.by() == null ? "" : " (" + nearest.by() + " did)")
                    + " at " + nearest.at().x() + " " + nearest.at().y() + " " + nearest.at().z()));
        }
        if (priors.byEyes(resource) || !priors.dimension(resource).equals(from.dimension())) return Optional.empty();
        int y = priors.bestHeight(resource, from.dimension()).orElse(from.y());
        return nearestFrontier(from, resource).map(key -> new Lead(key.centre(y), false,
                "the edge of what it knows, where " + name(resource) + " are likely"));
    }

    /**
     * The chunk on the edge of what it knows (stood in or seen into, with a neighbour it knows nothing of) in
     * {@code from}'s dimension that is best to go to: the nearest, with one whose biome the priors favour for
     * {@code want} counted at half its distance and one they rule out at twice it. Empty when it knows no chunk there.
     */
    public Optional<ChunkKey> nearestFrontier(Place from, Resource want) {
        ChunkKey best = null;
        double bestScore = Double.MAX_VALUE;
        for (var entry : chunks.entrySet()) {
            var key = entry.getKey();
            if (!key.dimension().equals(from.dimension()) || !frontier(key)) continue;
            double score = key.centre(from.y()).flatDistance(from);
            if (want != null && priors.byBiome(want) && entry.getValue().biome != null) {
                score *= priors.inBiome(want, entry.getValue().biome) ? 0.5 : 2.0;
            }
            if (score < bestScore) {
                bestScore = score;
                best = key;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether a known chunk has a side neighbour it knows nothing of. */
    boolean frontier(ChunkKey key) {
        return !chunks.containsKey(new ChunkKey(key.dimension(), key.x() + 1, key.z())) || !chunks.containsKey(new ChunkKey(key.dimension(), key.x() - 1, key.z()))
                || !chunks.containsKey(new ChunkKey(key.dimension(), key.x(), key.z() + 1)) || !chunks.containsKey(new ChunkKey(key.dimension(), key.x(), key.z() - 1));
    }

    /**
     * How dangerous the place's cell has been at this time of day: each hurt there weighs its weight, fading to nothing
     * over {@link #FADE}, and one at the other time of day a quarter of that.
     */
    public double dangerAt(Place at, boolean night, long now) {
        return weigh(danger.get(at.cell()), night, now);
    }

    /** The cells within {@code radius} blocks of {@code around} whose danger at this time of day is at least {@code atLeast}. */
    public Map<CellKey, Double> dangerNear(Place around, int radius, boolean night, long now, double atLeast) {
        var near = new LinkedHashMap<CellKey, Double>();
        for (var entry : danger.entrySet()) {
            var key = entry.getKey();
            if (!key.dimension().equals(around.dimension())) continue;
            double cx = key.x() * CELL + CELL / 2.0, cy = key.y() * CELL + CELL / 2.0, cz = key.z() * CELL + CELL / 2.0;
            if (Math.abs(cx - around.x()) > radius + CELL || Math.abs(cz - around.z()) > radius + CELL || Math.abs(cy - around.y()) > radius + CELL) continue;
            double weight = weigh(entry.getValue(), night, now);
            if (weight >= atLeast) near.put(key, weight);
        }
        return near;
    }

    private static double weigh(List<Harm> harms, boolean night, long now) {
        if (harms == null) return 0;
        double total = 0;
        for (Harm harm : harms) {
            long age = now - harm.time();
            if (age < 0 || age >= FADE) continue;
            total += harm.weight() * (1.0 - (double) age / FADE) * (harm.night() == night ? 1.0 : OTHER_TIME);
        }
        return total;
    }

    /** The nearest place it can shelter in {@code at}'s dimension within {@code within} blocks. */
    public Optional<Shelter> safeShelterNear(Place at, int within) {
        Shelter best = null;
        for (Shelter s : shelters) {
            if (!s.at().dimension().equals(at.dimension())) continue;
            double d = Math.sqrt(Math.pow(s.at().x() - at.x(), 2) + Math.pow(s.at().y() - at.y(), 2) + Math.pow(s.at().z() - at.z(), 2));
            if (d <= within && (best == null || d < Math.sqrt(Math.pow(best.at().x() - at.x(), 2) + Math.pow(best.at().y() - at.y(), 2)
                    + Math.pow(best.at().z() - at.z(), 2)))) best = s;
        }
        return Optional.ofNullable(best);
    }

    private static String name(Resource resource) {
        return switch (resource) {
            case DIAMOND -> "diamonds";
            case IRON -> "iron";
            case COAL -> "coal";
            case LAVA -> "lava";
            case WATER -> "water";
            case TREES -> "trees";
            case VILLAGE -> "villages";
            case FORTRESS -> "a fortress";
            case STRONGHOLD -> "the stronghold";
        };
    }

    // ------------------------------------------------------------------ kept

    /** A chunk as kept: where, when last seen, whether stood in, its biome, its sightings. */
    public record ChunkRow(String dimension, int x, int z, long last, boolean visited, String biome, List<Sighting> sightings) { }

    /** A cell of the danger map as kept, with its hurts. */
    public record CellRow(String dimension, int x, int y, int z, List<Harm> harms) { }

    /** All of it, as plain values, for saving. */
    public record Snapshot(List<ChunkRow> chunks, List<CellRow> danger, List<Shelter> shelters) { }

    public Snapshot snapshot() {
        var rows = new ArrayList<ChunkRow>(chunks.size());
        for (var entry : chunks.entrySet()) {
            var c = entry.getValue();
            rows.add(new ChunkRow(entry.getKey().dimension(), entry.getKey().x(), entry.getKey().z(), c.last, c.visited, c.biome, List.copyOf(c.sightings.values())));
        }
        var cells = new ArrayList<CellRow>(danger.size());
        for (var entry : danger.entrySet()) {
            cells.add(new CellRow(entry.getKey().dimension(), entry.getKey().x(), entry.getKey().y(), entry.getKey().z(), List.copyOf(entry.getValue())));
        }
        return new Snapshot(rows, cells, List.copyOf(shelters));
    }

    /** An atlas as a snapshot kept it; rows it cannot read (a missing dimension, edited by hand) are left out. */
    public static Atlas restore(Snapshot snapshot, Priors priors) {
        var atlas = new Atlas(priors);
        if (snapshot == null) return atlas;
        if (snapshot.chunks() != null) {
            for (ChunkRow row : snapshot.chunks()) {
                if (row == null || row.dimension() == null) continue;
                Chunk chunk = atlas.chunk(new ChunkKey(row.dimension(), row.x(), row.z()));
                chunk.last = row.last();
                chunk.visited = row.visited();
                chunk.biome = row.biome();
                if (row.sightings() != null) {
                    for (Sighting s : row.sightings()) if (s != null && s.resource() != null && s.at() != null) chunk.sightings.put(s.resource(), s);
                }
            }
        }
        if (snapshot.danger() != null) {
            for (CellRow row : snapshot.danger()) {
                if (row == null || row.dimension() == null || row.harms() == null) continue;
                for (Harm harm : row.harms()) {
                    if (harm != null) atlas.harmed(new Place(row.dimension(), row.x() * CELL, row.y() * CELL, row.z() * CELL), harm.night(), harm.time(), harm.weight());
                }
            }
        }
        if (snapshot.shelters() != null) {
            for (Shelter s : snapshot.shelters()) if (s != null && s.at() != null) atlas.shelter(s.at(), s.kind());
        }
        return atlas;
    }
}
