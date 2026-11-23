package dev.kindred.fabric;

/**
 * In plain terms: the order in which a new AI player's spawn tries the ground round where it is made, the same order a
 * human joining the world is tried in, so it lands on dry ground where a human would.
 *
 * Technical: vanilla 1.21.1's {@code ServerPlayerEntity.getWorldSpawnPos} order (M-06i4a): the radius is the
 * {@code spawnRadius} rule, cut to the distance inside the world border and at least 1; every column of the
 * (2r+1)² square once, from a random start with vanilla's stride ({@code calculateSpawnOffsetMultiplier}). The start is
 * seeded (by the creation's id) instead of {@code Random.create()}, so a creation tries the same order every time.
 * Unloaded columns are skipped before they are judged, so nothing is loaded or generated for them.
 */
final class SpawnColumns {
    private SpawnColumns() {}

    /** A column's verdict: the spot found, or {@code null} for a column refused. */
    interface Judge<T> { T at(int x, int z); }

    /** Whether a column's chunk is already loaded. */
    interface Loaded { boolean at(int x, int z); }

    /** Vanilla's radius: {@code spawnRadius} cut to the distance inside the border, at least 1. */
    static int radius(int spawnRadius, double insideBorder) {
        int r = Math.max(0, spawnRadius);
        int inside = (int) Math.floor(insideBorder);
        if (inside < r) r = inside;
        if (inside <= 1) r = 1;
        return r;
    }

    /** Vanilla's stride through the square. */
    static int stride(int area) { return area <= 16 ? area - 1 : 17; }

    /** The first column round ({@code cx}, {@code cz}) that is loaded and that {@code judge} accepts, in vanilla's order; or null. */
    static <T> T first(int cx, int cz, int radius, long seed, Loaded loaded, Judge<T> judge) {
        long side = radius * 2L + 1;
        long cells = side * side;
        int area = cells > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cells;
        int stride = stride(area);
        int start = new java.util.SplittableRandom(seed).nextInt(area);
        for (int step = 0; step < area; step++) {
            int cell = (int) ((start + (long) stride * step) % area);
            int x = cx + cell % (int) side - radius;
            int z = cz + cell / (int) side - radius;
            if (!loaded.at(x, z)) continue;
            T found = judge.at(x, z);
            if (found != null) return found;
        }
        return null;
    }
}
