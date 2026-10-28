package dev.kindred.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * In plain terms: the player's eyes for blocks. Each tick it looks over a few chunk sections near the player
 * for the blocks it cares about (logs, ores and so on), skipping unloaded and empty sections, and keeps what
 * it saw in a BlockObservations. It works to a strict per-tick budget so it never slows the server down.
 *
 * Technical: Incremental, budgeted block perception over a planned list of chunk sections. Each tick examines
 * at most {@link Budget#sectionsPerTick()} sections: unloaded sections are skipped without loading, empty
 * sections and sections whose palette holds no block of interest are skipped without visiting their blocks,
 * and at most {@link Budget#detailedSectionsPerTick()} sections are walked block by block. A pass over the
 * whole plan bumps the index version; facts carry their own observation time. The adapter supplies section
 * views on the server thread; this class never sees a world object and never blocks a tick on anything but
 * the budget.
 */
public final class BlockPerception {
    /** How much looking is allowed each tick. */
    public record Budget(int sectionsPerTick, int detailedSectionsPerTick, int matchesPerSection, int exposedPerSection) {
        public Budget {
            if (sectionsPerTick < 1 || sectionsPerTick > 64) throw new IllegalArgumentException("sectionsPerTick is 1..64");
            if (detailedSectionsPerTick < 1 || detailedSectionsPerTick > sectionsPerTick) throw new IllegalArgumentException("detailedSectionsPerTick is 1..sectionsPerTick");
            if (matchesPerSection < 1 || matchesPerSection > 4096) throw new IllegalArgumentException("matchesPerSection is 1..4096");
            if (exposedPerSection < 1 || exposedPerSection > matchesPerSection) throw new IllegalArgumentException("exposedPerSection is 1..matchesPerSection");
        }
    }

    /** Sixteen cheap checks or two detailed walks per tick; a nine-chunk, five-section plan completes in 3..23 ticks. */
    public static final Budget DEFAULT = new Budget(16, 2, 256, 32);

    /** Server-thread access to one section. Returning {@code null} means not loaded; it must never load a chunk. */
    public interface SectionSource {
        SectionView section(SectionKey key);
    }

    public interface SectionView {
        /** True when the section has no non-air blocks at all. */
        boolean isEmpty();

        /** Palette prefilter: may any block in this section be one of these identities? Must be O(palette). */
        boolean mayContain(Set<String> blockIds);

        /** Walk the section's matching blocks until the visitor returns false. */
        void visitMatches(Set<String> blockIds, MatchVisitor visitor);
    }

    @FunctionalInterface
    public interface MatchVisitor {
        boolean visit(WorkSetup.BlockPoint position, String blockId, boolean exposed);
    }

    /** Monotonic counters for diagnostics (OPS-03); they prove where the budget went. */
    public record Stats(long ticks, long sectionsVisited, long unloaded, long empty, long skippedByPalette, long scanned,
                        long passes, long lastPassTicks, long lastTickNanos, long maxTickNanos, PassStats lastPass) { }

    /** The same breakdown for the most recently completed pass only. */
    public record PassStats(long ticks, long unloaded, long empty, long skippedByPalette, long scanned) {
        public static final PassStats NONE = new PassStats(0, 0, 0, 0, 0);
    }

    private final String dimension;
    private final Budget budget;
    private final LongSupplier clockMillis;
    private final LongSupplier nanoTime;
    private List<SectionKey> plan = List.of();
    private Set<String> interest = Set.of();
    private final Map<SectionKey, BlockObservations.SectionFacts> facts = new HashMap<>();
    private BlockObservations snapshot;
    private int cursor;
    private long version;
    private int passTicks;
    private long passUnloaded, passEmpty, passSkipped, passScanned;
    private PassStats lastPass = PassStats.NONE;
    private long ticks, sectionsVisited, unloaded, empty, skippedByPalette, scanned, passes, lastPassTicks, lastTickNanos, maxTickNanos;

    /** New eyes for one dimension, with a budget and clocks. */
    public BlockPerception(String dimension, Budget budget, LongSupplier clockMillis, LongSupplier nanoTime) {
        this.dimension = Objects.requireNonNull(dimension);
        this.budget = Objects.requireNonNull(budget);
        this.clockMillis = Objects.requireNonNull(clockMillis);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.snapshot = BlockObservations.empty(dimension, Set.of());
    }

    public Budget budget() { return budget; }
    public List<SectionKey> plan() { return plan; }
    public Set<String> interest() { return interest; }
    public int cursor() { return cursor; }
    public boolean idle() { return plan.isEmpty() || interest.isEmpty(); }

    /**
     * Replace the plan and/or interest. Facts for sections that stay in the plan survive a move; a change of
     * interest discards everything because old facts answered a different question. Returns whether anything changed.
     */
    public boolean retarget(List<SectionKey> newPlan, Set<String> newInterest) {
        Objects.requireNonNull(newPlan);
        Set<String> wanted = Set.copyOf(newInterest);
        if (wanted.size() > BlockObservations.MAX_INTEREST) throw new IllegalArgumentException("At most " + BlockObservations.MAX_INTEREST + " blocks of interest");
        if (newPlan.size() > BlockObservations.MAX_SECTIONS) throw new IllegalArgumentException("Plan exceeds the section cap");
        boolean samePlan = newPlan.equals(plan);
        boolean sameInterest = wanted.equals(interest);
        if (samePlan && sameInterest) return false;
        if (!sameInterest) facts.clear();
        else facts.keySet().retainAll(new java.util.HashSet<>(newPlan));
        plan = List.copyOf(newPlan);
        interest = wanted;
        cursor = 0;
        passTicks = 0;
        passUnloaded = passEmpty = passSkipped = passScanned = 0;
        snapshot = null;
        return true;
    }

    /** Examine up to the budget's worth of sections. Call once per server tick while perception is admitted. */
    public Stats tick(SectionSource source) {
        Objects.requireNonNull(source);
        if (idle()) return stats();
        long started = nanoTime.getAsLong();
        long now = clockMillis.getAsLong();
        ticks++;
        passTicks++;
        int visited = 0;
        int detailed = 0;
        while (visited < budget.sectionsPerTick() && cursor < plan.size()) {
            SectionKey key = plan.get(cursor);
            SectionView view = source.section(key);
            if (view == null) {
                unloaded++;
                passUnloaded++;
                if (facts.remove(key) != null) snapshot = null; // Unknown again; stale facts are not still true.
            } else if (view.isEmpty()) {
                empty++;
                passEmpty++;
                record(key, BlockObservations.SectionFacts.nothing(now));
            } else if (!view.mayContain(interest)) {
                skippedByPalette++;
                passSkipped++;
                record(key, BlockObservations.SectionFacts.nothing(now));
            } else {
                if (detailed >= budget.detailedSectionsPerTick()) break; // Leave it for the next tick; do not advance.
                detailed++;
                scanned++;
                passScanned++;
                record(key, walk(view, now));
            }
            visited++;
            sectionsVisited++;
            cursor++;
        }
        if (cursor >= plan.size()) {
            cursor = 0;
            version++;
            passes++;
            lastPassTicks = passTicks;
            lastPass = new PassStats(passTicks, passUnloaded, passEmpty, passSkipped, passScanned);
            passTicks = 0;
            passUnloaded = passEmpty = passSkipped = passScanned = 0;
            snapshot = null;
        }
        lastTickNanos = Math.max(0, nanoTime.getAsLong() - started);
        maxTickNanos = Math.max(maxTickNanos, lastTickNanos);
        return stats();
    }

    /** Looks through one section block by block and notes the matches. */
    private BlockObservations.SectionFacts walk(SectionView view, long now) {
        Map<String, Integer> matches = new LinkedHashMap<>();
        List<BlockObservation> exposed = new ArrayList<>();
        int[] seen = {0};
        view.visitMatches(interest, (position, blockId, isExposed) -> {
            if (!interest.contains(blockId)) return true; // A view must not widen the question.
            seen[0]++;
            matches.merge(blockId, 1, Integer::sum);
            if (isExposed && exposed.size() < budget.exposedPerSection()) exposed.add(new BlockObservation(position, blockId, now));
            return seen[0] < budget.matchesPerSection();
        });
        return new BlockObservations.SectionFacts(now, matches, exposed);
    }

    /** Stores what was seen in one section. */
    private void record(SectionKey key, BlockObservations.SectionFacts next) {
        facts.put(key, next);
        snapshot = null;
    }

    /** Immutable view of everything currently known; rebuilt lazily after a change. */
    public BlockObservations snapshot() {
        if (snapshot == null) snapshot = new BlockObservations(dimension, version, interest, facts);
        return snapshot;
    }

    /** Counters showing where the looking budget went, for diagnosing performance. */
    public Stats stats() {
        return new Stats(ticks, sectionsVisited, unloaded, empty, skippedByPalette, scanned, passes, lastPassTicks, lastTickNanos, maxTickNanos, lastPass);
    }
}
