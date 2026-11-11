package dev.kindred.fabric;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: a player who has walked through a forest and remembers as many logs as it can still learns of the tree
 * beside it; what it forgets are the ones farthest behind it.
 *
 * Technical: WorldKnowledge's per-kind cap. At the cap, remembering a new block drops the farthest tenth of that kind from
 * where it last looked round, instead of refusing the new one (recap-factions110-b663fb5: "0 known" with 42 to 82 logs
 * within 8 of a stuck player).
 */
class WorldKnowledgeCapTest {
    private static final int CAP = WorldKnowledge.maxPerKind();

    private static WorldKnowledge full(BlockPos center) {
        var knowledge = new WorldKnowledge();
        knowledge.centerAt(center);
        // Logs far behind it, 1,000 to 1,000 + CAP blocks east along one row.
        for (int i = 0; i < CAP; i++) knowledge.remember(WorldKnowledge.Kind.LOG, new BlockPos(1000 + i, 64, 0));
        assertEquals(CAP, knowledge.count(WorldKnowledge.Kind.LOG), "full");
        return knowledge;
    }

    @Test
    void atTheCapTheTreeBesideItIsRememberedAndTheFarthestGo() {
        var knowledge = full(BlockPos.ORIGIN);
        BlockPos beside = new BlockPos(4, 64, 0);
        knowledge.remember(WorldKnowledge.Kind.LOG, beside);
        var all = knowledge.all(WorldKnowledge.Kind.LOG);
        assertTrue(all.contains(beside), "the tree beside it is known");
        assertTrue(all.size() <= CAP, "never more than the cap: " + all.size());
        assertFalse(all.contains(new BlockPos(1000 + CAP - 1, 64, 0)), "the farthest is gone");
        assertTrue(all.contains(new BlockPos(1000, 64, 0)), "the nearest of the far ones is kept");
        assertEquals(CAP - WorldKnowledge.EVICT + 1, all.size(), "a tenth went, once");
    }

    @Test
    void aBlockAlreadyKnownAtTheCapEvictsNothing() {
        var knowledge = full(BlockPos.ORIGIN);
        knowledge.remember(WorldKnowledge.Kind.LOG, new BlockPos(1500, 64, 0));
        assertEquals(CAP, knowledge.count(WorldKnowledge.Kind.LOG), "nothing evicted for one it knew");
    }

    @Test
    void otherKindsAreUntouched() {
        var knowledge = full(BlockPos.ORIGIN);
        knowledge.remember(WorldKnowledge.Kind.IRON_ORE, new BlockPos(2000, 20, 0));
        knowledge.remember(WorldKnowledge.Kind.LOG, new BlockPos(4, 64, 0));
        assertEquals(1, knowledge.count(WorldKnowledge.Kind.IRON_ORE), "a far ore of another kind is kept");
    }

    @Test
    void rememberingAtTheCapStaysCheap() {
        // A round first, so class loading and the JIT are not timed (cold, the first round took about 30 µs a call).
        fill(full(BlockPos.ORIGIN));
        var knowledge = full(BlockPos.ORIGIN);
        long start = System.nanoTime();
        fill(knowledge);
        double perCall = (System.nanoTime() - start) / 20_000.0 / 1000.0;
        assertTrue(knowledge.count(WorldKnowledge.Kind.LOG) <= CAP, "within the cap");
        // Bound: well under a tick's share. Measured warm at 1.6 to 4.4 µs a call on this machine; 50 µs allows a slow CI one.
        assertTrue(perCall < 50, "remember() at the cap: " + perCall + " µs a call, amortised");
    }

    /** 20,000 new logs remembered at the cap: 100 evictions of a tenth each, the rest plain puts. */
    private static void fill(WorldKnowledge knowledge) {
        for (int i = 0; i < 20_000; i++) knowledge.remember(WorldKnowledge.Kind.LOG, new BlockPos(i % 200, 64, 10 + i / 200));
    }
}
