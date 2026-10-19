package dev.kindred.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Which chunk sections a companion may perceive right now: the allocated work area, clipped to a few chunks
 * around the body and a vertical band around its feet, inside the world's height. Nearest sections come
 * first so a budgeted pass improves the most relevant facts earliest. This is range, not permission.
 */
public final class SectionPlanner {
    /** Horizontal radius in chunks and vertical radius in sections around the body's own section. */
    public record Range(int horizontalChunks, int verticalSections) {
        public Range {
            if (horizontalChunks < 0 || horizontalChunks > 2) throw new IllegalArgumentException("Horizontal range is 0..2 chunks");
            if (verticalSections < 0 || verticalSections > 4) throw new IllegalArgumentException("Vertical range is 0..4 sections");
        }

        public int maxSections() {
            int side = 2 * horizontalChunks + 1;
            return Math.min(side * side, WorkSetup.MAX_AREA_CHUNKS) * (2 * verticalSections + 1);
        }
    }

    /** Nine chunks at most (the area cap) by five sections: 80 blocks of height around the body. */
    public static final Range DEFAULT = new Range(1, 2);

    private SectionPlanner() { }

    public static List<SectionKey> plan(WorkSetup.WorkRegion area, WorkSetup.BlockPoint center, Range range,
                                        int bottomSectionY, int topSectionYExclusive) {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(range, "range");
        if (bottomSectionY >= topSectionYExclusive) return List.of();
        SectionKey origin = SectionKey.of(center);
        int minCx = Math.max(area.minX() >> 4, origin.x() - range.horizontalChunks());
        int maxCx = Math.min(area.maxX() >> 4, origin.x() + range.horizontalChunks());
        int minCz = Math.max(area.minZ() >> 4, origin.z() - range.horizontalChunks());
        int maxCz = Math.min(area.maxZ() >> 4, origin.z() + range.horizontalChunks());
        int minSy = Math.max(bottomSectionY, origin.y() - range.verticalSections());
        int maxSy = Math.min(topSectionYExclusive - 1, origin.y() + range.verticalSections());
        List<SectionKey> plan = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int sy = minSy; sy <= maxSy; sy++) plan.add(new SectionKey(cx, sy, cz));
            }
        }
        plan.sort(Comparator.comparingLong((SectionKey key) -> key.squaredDistance(origin))
                .thenComparingInt(SectionKey::y).thenComparingInt(SectionKey::x).thenComparingInt(SectionKey::z));
        if (plan.size() > BlockObservations.MAX_SECTIONS) throw new IllegalStateException("Plan exceeds the section cap: " + plan.size());
        return List.copyOf(plan);
    }
}
