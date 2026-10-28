package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that a player only finds blocks it could really see, never through walls, and stays
 * within its search budget.
 */
class BlockPerceptionTest {
    private static final String LOG = "minecraft:oak_log";
    private static final String CHEST = "minecraft:chest";
    private static final String STONE = "minecraft:stone";
    private final long[] clock = {1_000};
    private final long[] nanos = {0};

    /** A fake world of sections: absent key = unloaded; a view counts how often its blocks were walked. */
    private static final class FakeWorld implements BlockPerception.SectionSource {
        final Map<SectionKey, FakeSection> sections = new HashMap<>();
        @Override public BlockPerception.SectionView section(SectionKey key) { return sections.get(key); }
        FakeSection put(SectionKey key) { return sections.computeIfAbsent(key, k -> new FakeSection(k)); }
    }

    private static final class FakeSection implements BlockPerception.SectionView {
        final SectionKey key;
        final List<Object[]> blocks = new ArrayList<>(); // {BlockPoint, id, exposed}
        int walks;
        FakeSection(SectionKey key) { this.key = key; }
        FakeSection with(int dx, int dy, int dz, String id, boolean exposed) {
            blocks.add(new Object[]{new WorkSetup.BlockPoint(key.minBlockX() + dx, key.minBlockY() + dy, key.minBlockZ() + dz), id, exposed});
            return this;
        }
        @Override public boolean isEmpty() { return blocks.isEmpty(); }
        @Override public boolean mayContain(Set<String> ids) { return blocks.stream().anyMatch(b -> ids.contains((String) b[1])); }
        @Override public void visitMatches(Set<String> ids, BlockPerception.MatchVisitor visitor) {
            walks++;
            for (Object[] b : blocks) {
                if (ids.contains((String) b[1]) && !visitor.visit((WorkSetup.BlockPoint) b[0], (String) b[1], (Boolean) b[2])) return;
            }
        }
    }

    private BlockPerception perception(BlockPerception.Budget budget) {
        return new BlockPerception("minecraft:overworld", budget, () -> clock[0], () -> nanos[0]);
    }

    @Test
    void plannerClipsToAreaRangeAndWorldHeightAndOrdersNearestFirst() {
        var home = new WorkSetup.BlockPoint(100, 64, 100); // section 6,4,6
        WorkSetup.WorkRegion area = WorkSetup.WorkRegion.around(home); // chunks 5..7 x 5..7
        List<SectionKey> plan = SectionPlanner.plan(area, home, SectionPlanner.DEFAULT, -4, 20);
        assertEquals(45, plan.size(), "nine chunks by five sections");
        assertEquals(new SectionKey(6, 4, 6), plan.get(0), "the body's own section comes first");
        for (int i = 1; i < plan.size(); i++) {
            assertTrue(plan.get(i - 1).squaredDistance(plan.get(0)) <= plan.get(i).squaredDistance(plan.get(0)), "nearest first");
        }
        assertTrue(plan.stream().allMatch(k -> k.x() >= 5 && k.x() <= 7 && k.z() >= 5 && k.z() <= 7 && k.y() >= 2 && k.y() <= 6));
        // A long thin area: range clips what the area allows, never the other way round.
        WorkSetup.WorkRegion strip = new WorkSetup.WorkRegion(0, 0, 143, 15); // 9 x 1 chunks
        List<SectionKey> clipped = SectionPlanner.plan(strip, new WorkSetup.BlockPoint(8, 64, 8), SectionPlanner.DEFAULT, -4, 20);
        assertEquals(2 * 5, clipped.size(), "chunk 0 and 1 only; chunk -1 is outside the area");
        assertTrue(clipped.stream().allMatch(k -> k.x() >= 0 && k.x() <= 1 && k.z() == 0));
        // World height clamps the vertical band; the top coordinate is exclusive.
        List<SectionKey> low = SectionPlanner.plan(area, new WorkSetup.BlockPoint(100, -60, 100), SectionPlanner.DEFAULT, -4, 20);
        assertTrue(low.stream().allMatch(k -> k.y() >= -4 && k.y() <= -2));
        List<SectionKey> high = SectionPlanner.plan(area, new WorkSetup.BlockPoint(100, 318, 100), SectionPlanner.DEFAULT, -4, 20);
        assertTrue(high.stream().allMatch(k -> k.y() <= 19 && k.y() >= 17));
        assertEquals(List.of(), SectionPlanner.plan(area, home, SectionPlanner.DEFAULT, 5, 5));
        assertEquals(45, SectionPlanner.DEFAULT.maxSections());
        assertThrows(IllegalArgumentException.class, () -> new SectionPlanner.Range(3, 1));
        assertThrows(IllegalArgumentException.class, () -> new SectionPlanner.Range(1, 5));
    }

    @Test
    void paletteFilterAvoidsBlockWalksAndTheBudgetBoundsEveryTick() {
        FakeWorld world = new FakeWorld();
        List<SectionKey> plan = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            SectionKey key = new SectionKey(i, 4, 0);
            plan.add(key);
            FakeSection section = world.put(key).with(1, 1, 1, STONE, true);
            if (i % 3 == 0) section.with(2, 2, 2, LOG, true); // sections 0, 3, 6, 9 hold a log
        }
        BlockPerception perception = perception(new BlockPerception.Budget(4, 1, 256, 32));
        assertTrue(perception.retarget(plan, Set.of(LOG)));
        assertFalse(perception.retarget(plan, Set.of(LOG)), "same question is not a change");

        var first = perception.tick(world);
        // Section 0 walked (detailed 1), sections 1 and 2 palette-skipped, section 3 needs a walk -> stop before it.
        assertEquals(3, first.sectionsVisited());
        assertEquals(1, first.scanned());
        assertEquals(2, first.skippedByPalette());
        assertEquals(3, perception.cursor(), "a section the budget could not afford is not advanced past");
        assertEquals(0, first.passes());

        int ticks = 1;
        while (perception.stats().passes() == 0 && ticks < 50) { perception.tick(world); ticks++; }
        var done = perception.stats();
        assertEquals(1, done.passes());
        assertEquals(4, done.scanned(), "only the four sections whose palette holds a log were walked");
        assertEquals(6, done.skippedByPalette());
        assertEquals(10, done.sectionsVisited());
        assertEquals(ticks, done.lastPassTicks());
        assertEquals(new BlockPerception.PassStats(ticks, 0, 0, 6, 4), done.lastPass());
        for (FakeSection section : world.sections.values()) assertTrue(section.walks <= 1, "no section walked twice in one pass");
        BlockObservations snapshot = perception.snapshot();
        assertEquals(1, snapshot.version(), "a completed pass is a new version");
        assertEquals(4, snapshot.exposedCount(LOG));
        assertEquals(10, snapshot.sections().size(), "skipped sections are still recorded as observed-nothing");
        assertEquals(0, perception.cursor(), "the next pass starts over");
    }

    @Test
    void hiddenMatchesAreCountedButNeverPositionedAndSectionCapsApply() {
        FakeWorld world = new FakeWorld();
        SectionKey key = new SectionKey(0, 4, 0);
        FakeSection section = world.put(key);
        for (int i = 0; i < 300; i++) section.with(i % 16, (i / 16) % 16, i / 256, STONE, i < 40); // 40 exposed of 300
        section.with(0, 15, 15, LOG, false); // buried log
        BlockPerception perception = perception(new BlockPerception.Budget(16, 2, 256, 32));
        perception.retarget(List.of(key), Set.of(STONE, LOG));
        perception.tick(world);
        BlockObservations snapshot = perception.snapshot();
        assertEquals(256, snapshot.matchCount(STONE), "the walk stops at the per-section match cap");
        assertEquals(32, snapshot.exposedCount(STONE), "exposed positions are capped separately");
        assertEquals(0, snapshot.matchCount(LOG), "the buried log came after the cap and is simply unknown");
        // Without the cap pressure, a buried match is counted but never positioned.
        FakeSection quiet = world.put(new SectionKey(1, 4, 0)).with(3, 3, 3, LOG, false).with(4, 3, 3, LOG, true);
        perception.retarget(List.of(quiet.key), Set.of(LOG));
        perception.tick(world);
        snapshot = perception.snapshot();
        assertEquals(2, snapshot.matchCount(LOG));
        assertEquals(1, snapshot.exposedCount(LOG));
        assertEquals(new WorkSetup.BlockPoint(20, 67, 3), snapshot.nearest(LOG, new WorkSetup.BlockPoint(0, 0, 0), 5).get(0).position());
        assertThrows(IllegalArgumentException.class, () -> new BlockPerception.Budget(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BlockPerception.Budget(4, 5, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BlockPerception.Budget(4, 1, 10, 11));
    }

    @Test
    void retargetKeepsFactsInsideTheNewPlanDropsTheRestAndClearsOnANewQuestion() {
        FakeWorld world = new FakeWorld();
        SectionKey a = new SectionKey(0, 4, 0), b = new SectionKey(1, 4, 0), c = new SectionKey(2, 4, 0);
        world.put(a).with(1, 1, 1, LOG, true);
        world.put(b).with(1, 1, 1, LOG, true);
        world.put(c).with(1, 1, 1, CHEST, true);
        BlockPerception perception = perception(BlockPerception.DEFAULT);
        perception.retarget(List.of(a, b), Set.of(LOG));
        perception.tick(world);
        assertEquals(2, perception.snapshot().exposedCount(LOG));
        // Moving so that a leaves range: a's facts go, b's stay, and the pass restarts.
        assertTrue(perception.retarget(List.of(b, c), Set.of(LOG)));
        assertEquals(1, perception.snapshot().exposedCount(LOG));
        assertTrue(perception.snapshot().sections().containsKey(b));
        assertFalse(perception.snapshot().sections().containsKey(a));
        assertEquals(0, perception.cursor());
        // A new question discards old answers.
        assertTrue(perception.retarget(List.of(b, c), Set.of(CHEST)));
        assertEquals(0, perception.snapshot().sections().size());
        perception.tick(world);
        assertEquals(1, perception.snapshot().exposedCount(CHEST));
        assertEquals(0, perception.snapshot().matchCount(LOG));
        assertThrows(IllegalArgumentException.class, () -> perception.retarget(List.of(a),
                Set.of("a", "b", "c", "d", "e", "f", "g", "h", "i")));
    }

    @Test
    void unloadedSectionsAreSkippedWithoutObservationAndForgetStaleFacts() {
        FakeWorld world = new FakeWorld();
        SectionKey loaded = new SectionKey(0, 4, 0), missing = new SectionKey(5, 4, 5);
        world.put(loaded).with(1, 1, 1, LOG, true);
        world.put(missing).with(1, 1, 1, LOG, true);
        BlockPerception perception = perception(BlockPerception.DEFAULT);
        perception.retarget(List.of(loaded, missing), Set.of(LOG));
        perception.tick(world);
        assertEquals(2, perception.snapshot().exposedCount(LOG));
        world.sections.remove(missing); // The chunk unloads between passes.
        clock[0] += 5_000;
        var stats = perception.tick(world);
        assertEquals(1, stats.unloaded());
        assertEquals(1, perception.snapshot().exposedCount(LOG), "facts about an unloaded section are unknown, not still true");
        assertFalse(perception.snapshot().sections().containsKey(missing));
        assertEquals(6_000, perception.snapshot().sections().get(loaded).observedAtMillis());
        assertEquals(2, perception.snapshot().version());
        // Idle perception does nothing and costs nothing.
        BlockPerception idle = perception(BlockPerception.DEFAULT);
        assertTrue(idle.idle());
        assertEquals(0, idle.tick(world).ticks());
        idle.retarget(List.of(loaded), Set.of());
        assertTrue(idle.idle(), "no blocks of interest means no scan");
    }

    @Test
    void snapshotsAreImmutableFactsWithDistanceOrderPatchFilterAndAgeLimits() {
        var near = new BlockObservation(new WorkSetup.BlockPoint(10, 64, 10), LOG, 1_000);
        var far = new BlockObservation(new WorkSetup.BlockPoint(30, 64, 30), LOG, 1_000);
        var other = new BlockObservation(new WorkSetup.BlockPoint(11, 64, 10), CHEST, 1_000);
        var facts = new BlockObservations.SectionFacts(1_000, Map.of(LOG, 3, CHEST, 1), List.of(far, near, other));
        BlockObservations index = BlockObservations.empty("minecraft:overworld", Set.of(LOG, CHEST)).withSection(near.section(), facts);
        var from = new WorkSetup.BlockPoint(12, 64, 12);
        assertEquals(List.of(near, far), index.nearest(LOG, from, 5));
        assertEquals(List.of(near), index.nearest(LOG, from, 1));
        var patch = new WorkSetup.ResourcePatch(new WorkSetup.BlockPoint(20, 60, 20), new WorkSetup.BlockPoint(35, 70, 35), LOG);
        assertEquals(List.of(far), index.nearest(LOG, from, 5, patch::contains), "a match outside the patch is visible but filtered out for work");
        assertEquals(3, index.matchCount(LOG));
        assertEquals(2, index.exposedCount(LOG));
        assertEquals(3, index.summary().get(LOG)[0]);
        assertThrows(UnsupportedOperationException.class, () -> index.sections().clear());
        assertThrows(UnsupportedOperationException.class, () -> index.nearest(LOG, from, 5).clear());
        assertThrows(IllegalArgumentException.class, () -> new BlockObservations.SectionFacts(1, Map.of(), List.of(near)),
                "an exposed observation needs a match count for its block");
        assertSame(index, index.dropOlderThan(2_000, 5_000));
        assertEquals(0, index.dropOlderThan(10_000, 5_000).sections().size());
        assertEquals(index.without(near.section()).sections().size(), 0);
        assertSame(index, index.without(new SectionKey(99, 99, 99)));
        assertEquals(9_000, near.ageMillis(10_000));
        assertEquals(0, near.ageMillis(500), "clock rollback is not a negative age");
    }
}
