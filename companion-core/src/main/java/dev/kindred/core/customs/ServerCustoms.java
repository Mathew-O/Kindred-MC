package dev.kindred.core.customs;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: what the players learn of a server's ways from the people who said they may: the depths those
 * people mine their ores at, put together. A person opts in for themselves, and opting out takes back all that was
 * learned from them. Nothing is learned from anyone else, and nothing from what anyone says or carries. The players
 * lean on it only a little: their own depth for an ore moves at most three tenths of the way toward the people's, and
 * never more than eight blocks.
 *
 * Technical: E-47, under D10. Kept as counts by height, a bucket of four blocks, per ore and per consenting person:
 * aggregates, never events (no time, place or order of anything mined is kept). A person's counts are kept apart only
 * so that opting out can delete them exactly; the custom is their sum over everyone consenting. A custom stands only
 * on {@link #MIN_MINED} ores mined in all, and is their median height. {@link #blend} moves a prior height by at most
 * {@link #WEIGHT} of the way toward it and by at most {@link #MAX_SHIFT} blocks, the bound kept as personality's
 * bias is kept. Not thread-safe: the adapter uses it on the server thread.
 */
public final class ServerCustoms {
    /** The ores whose mining depth is learned. */
    public enum Ore { COAL, IRON, DIAMOND }

    /** How far the people's custom moves a prior height: three tenths of the way (the design's 70/30). */
    public static final double WEIGHT = 0.3;
    /** And never more than this many blocks. */
    public static final int MAX_SHIFT = 8;
    /** A custom stands on this many ores mined in all, among everyone consenting. */
    public static final int MIN_MINED = 16;
    /** Heights grouped by four blocks, from the world's floor at y -64 to its ceiling at 319. */
    static final int BUCKET = 4, FLOOR = -64, BUCKETS = (320 - FLOOR) / BUCKET;

    private final Set<UUID> consenting = new HashSet<>();
    private final Map<UUID, Map<Ore, int[]>> mined = new HashMap<>();

    /** What the customs hold, to be saved: who consents, and each one's counts by ore and height bucket. */
    public record Snapshot(List<UUID> consenting, Map<UUID, Map<Ore, int[]>> mined) {
        public Snapshot {
            consenting = consenting == null ? List.of() : consenting.stream().filter(Objects::nonNull).toList();
            var copy = new HashMap<UUID, Map<Ore, int[]>>();
            if (mined != null) mined.forEach((who, byOre) -> {
                if (who == null || byOre == null) return;
                var ores = new HashMap<Ore, int[]>();
                byOre.forEach((ore, counts) -> { if (ore != null && counts != null) ores.put(ore, counts.clone()); });
                copy.put(who, Map.copyOf(ores));
            });
            mined = Map.copyOf(copy);
        }
    }

    /** Whether {@code who} has opted in. */
    public boolean consents(UUID who) {
        return consenting.contains(Objects.requireNonNull(who));
    }

    /** {@code who} opts in, for themselves. True if they had not already. */
    public boolean optIn(UUID who) {
        return consenting.add(Objects.requireNonNull(who));
    }

    /** {@code who} opts out: their consent and everything learned from them are deleted. True if there was either. */
    public boolean optOut(UUID who) {
        Objects.requireNonNull(who);
        boolean was = consenting.remove(who);
        return mined.remove(who) != null || was;
    }

    /** {@code who} mined {@code ore} at height {@code y}; counted only if they consent, and only within the world's heights. */
    public void mined(UUID who, Ore ore, int y) {
        Objects.requireNonNull(who);
        Objects.requireNonNull(ore);
        if (!consenting.contains(who) || y < FLOOR || y >= FLOOR + BUCKETS * BUCKET) return;
        mined.computeIfAbsent(who, k -> new HashMap<>()).computeIfAbsent(ore, k -> new int[BUCKETS])[(y - FLOOR) / BUCKET]++;
    }

    /** How many {@code ore} the people consenting have mined, all told. */
    public int minedCount(Ore ore) {
        int total = 0;
        for (var byOre : mined.values()) {
            int[] counts = byOre.get(ore);
            if (counts != null) for (int n : counts) total += n;
        }
        return total;
    }

    /** The height the people consenting mine {@code ore} at: the median, by bucket middles; empty under {@link #MIN_MINED}. */
    public OptionalInt miningHeight(Ore ore) {
        int[] sum = new int[BUCKETS];
        int total = 0;
        for (var byOre : mined.values()) {
            int[] counts = byOre.get(ore);
            if (counts == null) continue;
            for (int b = 0; b < BUCKETS; b++) {
                sum[b] += counts[b];
                total += counts[b];
            }
        }
        if (total < MIN_MINED) return OptionalInt.empty();
        int half = (total + 1) / 2, seen = 0;
        for (int b = 0; b < BUCKETS; b++) {
            seen += sum[b];
            if (seen >= half) return OptionalInt.of(FLOOR + b * BUCKET + BUCKET / 2);
        }
        return OptionalInt.empty();
    }

    /**
     * {@code prior} moved toward the people's height for {@code ore}: by {@link #WEIGHT} of the way, at most
     * {@link #MAX_SHIFT} blocks; {@code prior} itself while there is no custom.
     */
    public int blend(Ore ore, int prior) {
        var custom = miningHeight(ore);
        if (custom.isEmpty()) return prior;
        double shift = WEIGHT * (custom.getAsInt() - prior);
        return prior + (int) Math.round(Math.max(-MAX_SHIFT, Math.min(MAX_SHIFT, shift)));
    }

    /** A copy of what is held, for saving. */
    public Snapshot snapshot() {
        return new Snapshot(List.copyOf(consenting), mined);
    }

    /** The customs from a saved snapshot; counts of anyone no longer consenting, and malformed ones, left out. */
    public static ServerCustoms of(Snapshot snapshot) {
        var customs = new ServerCustoms();
        if (snapshot == null) return customs;
        if (snapshot.consenting() != null) for (UUID who : snapshot.consenting()) if (who != null) customs.consenting.add(who);
        if (snapshot.mined() != null) {
            snapshot.mined().forEach((who, byOre) -> {
                if (who == null || byOre == null || !customs.consenting.contains(who)) return;
                byOre.forEach((ore, counts) -> {
                    if (ore == null || counts == null || counts.length != BUCKETS) return;
                    int[] kept = new int[BUCKETS];
                    for (int b = 0; b < BUCKETS; b++) kept[b] = Math.max(0, counts[b]);
                    customs.mined.computeIfAbsent(who, k -> new HashMap<>()).put(ore, kept);
                });
            });
        }
        return customs;
    }
}
