package dev.kindred.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Immutable per-companion index of observed blocks of interest, keyed by chunk section. It is the bounded,
 * loaded, nearby observation WRLD-03 allows the brain to have: every fact has an observation time, sections
 * outside the current plan are absent, and nothing here is permission to act. Consumers recheck the live block
 * before any world action.
 */
public record BlockObservations(String dimension, long version, Set<String> interest, Map<SectionKey, SectionFacts> sections) {
    public static final int MAX_SECTIONS = 128;
    public static final int MAX_INTEREST = 8;

    /** What one section looked like at {@code observedAtMillis}: per-block match counts plus the exposed positions. */
    public record SectionFacts(long observedAtMillis, Map<String, Integer> matches, List<BlockObservation> exposed) {
        public SectionFacts {
            matches = Map.copyOf(matches);
            exposed = List.copyOf(exposed);
            for (BlockObservation observation : exposed) {
                if (!matches.containsKey(observation.blockId())) {
                    throw new IllegalArgumentException("Exposed observation without a match count: " + observation.blockId());
                }
            }
        }

        public static SectionFacts nothing(long observedAtMillis) { return new SectionFacts(observedAtMillis, Map.of(), List.of()); }

        public int matchCount() { return matches.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    public BlockObservations {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Observations need a dimension");
        if (version < 0) throw new IllegalArgumentException("Negative observation version");
        interest = Set.copyOf(interest);
        if (interest.size() > MAX_INTEREST) throw new IllegalArgumentException("At most " + MAX_INTEREST + " blocks of interest");
        sections = Map.copyOf(sections);
        if (sections.size() > MAX_SECTIONS) throw new IllegalArgumentException("Observation index exceeds " + MAX_SECTIONS + " sections");
    }

    public static BlockObservations empty(String dimension, Set<String> interest) {
        return new BlockObservations(dimension, 0, interest, Map.of());
    }

    public BlockObservations withSection(SectionKey key, SectionFacts facts) {
        Map<SectionKey, SectionFacts> next = new HashMap<>(sections);
        next.put(Objects.requireNonNull(key), Objects.requireNonNull(facts));
        return new BlockObservations(dimension, version, interest, next);
    }

    public BlockObservations without(SectionKey key) {
        if (!sections.containsKey(key)) return this;
        Map<SectionKey, SectionFacts> next = new HashMap<>(sections);
        next.remove(key);
        return new BlockObservations(dimension, version, interest, next);
    }

    /** Keep only sections still in a plan; everything else is out of range and therefore unknown again. */
    public BlockObservations retain(Collection<SectionKey> keys) {
        Map<SectionKey, SectionFacts> next = new HashMap<>();
        for (SectionKey key : keys) {
            SectionFacts facts = sections.get(key);
            if (facts != null) next.put(key, facts);
        }
        return new BlockObservations(dimension, version, interest, next);
    }

    public BlockObservations withVersion(long version) { return new BlockObservations(dimension, version, interest, sections); }

    public int matchCount(String blockId) {
        int total = 0;
        for (SectionFacts facts : sections.values()) total += facts.matches().getOrDefault(blockId, 0);
        return total;
    }

    public int exposedCount(String blockId) {
        int total = 0;
        for (SectionFacts facts : sections.values()) {
            for (BlockObservation observation : facts.exposed()) if (observation.blockId().equals(blockId)) total++;
        }
        return total;
    }

    /** Per-block totals across the index, in interest order, for status text. */
    public Map<String, int[]> summary() {
        Map<String, int[]> summary = new LinkedHashMap<>();
        for (String id : interest) summary.put(id, new int[]{matchCount(id), exposedCount(id)});
        return summary;
    }

    public List<BlockObservation> nearest(String blockId, WorkSetup.BlockPoint from, int limit) {
        return nearest(blockId, from, limit, point -> true);
    }

    /** Exposed matches sorted by distance, optionally restricted (for example to a designated resource patch). */
    public List<BlockObservation> nearest(String blockId, WorkSetup.BlockPoint from, int limit, Predicate<WorkSetup.BlockPoint> within) {
        if (limit <= 0) return List.of();
        List<BlockObservation> candidates = new ArrayList<>();
        for (SectionFacts facts : sections.values()) {
            for (BlockObservation observation : facts.exposed()) {
                if (observation.blockId().equals(blockId) && within.test(observation.position())) candidates.add(observation);
            }
        }
        candidates.sort(Comparator.comparingLong((BlockObservation o) -> o.squaredDistanceTo(from))
                .thenComparingInt(o -> o.position().y()).thenComparingInt(o -> o.position().x()).thenComparingInt(o -> o.position().z()));
        return List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
    }

    public OptionalLong oldestObservationMillis() {
        return sections.values().stream().mapToLong(SectionFacts::observedAtMillis).min();
    }

    public OptionalLong newestObservationMillis() {
        return sections.values().stream().mapToLong(SectionFacts::observedAtMillis).max();
    }

    /** Facts older than the limit are treated as unknown, not as still true. */
    public BlockObservations dropOlderThan(long now, long maxAgeMillis) {
        Map<SectionKey, SectionFacts> next = new HashMap<>();
        for (Map.Entry<SectionKey, SectionFacts> entry : sections.entrySet()) {
            if (now - entry.getValue().observedAtMillis() <= maxAgeMillis) next.put(entry.getKey(), entry.getValue());
        }
        return next.size() == sections.size() ? this : new BlockObservations(dimension, version, interest, next);
    }
}
