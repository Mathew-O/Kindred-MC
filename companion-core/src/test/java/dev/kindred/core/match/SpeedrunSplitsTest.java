package dev.kindred.core.match;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the checkpoints of a speedrun race: they are the game's own advancements from
 * getting iron to killing the dragon, each runner's checkpoint is counted once at the time since the start,
 * and times read like a stopwatch.
 */
class SpeedrunSplitsTest {
    private final UUID ada = UUID.randomUUID(), bram = UUID.randomUUID();

    @Test
    void eachSplitIsTakenOnceAtTheTimeSinceTheStart() {
        var splits = new SpeedrunSplits(1000);
        var iron = SpeedrunSplits.ANY_PERCENT.getFirst();
        var reached = splits.reach(ada, iron, 1000 + 20 * 95).orElseThrow();
        assertEquals(20 * 95, reached.ticks());
        assertFalse(reached.finish());
        assertTrue(splits.reach(ada, iron, 5000).isEmpty(), "once");
        assertTrue(splits.reach(bram, iron, 5000).isPresent(), "each runner its own");
        var dragon = splits.reach(ada, SpeedrunSplits.ANY_PERCENT.getLast(), 9000).orElseThrow();
        assertTrue(dragon.finish(), "the dragon is the finish");
        assertEquals(2, splits.of(ada).size());
        assertEquals("iron", splits.of(ada).getFirst().split().id(), "in the order reached");
    }

    @Test
    void theSplitsAreVanillaAdvancementsFromIronToTheDragon() {
        assertEquals(6, SpeedrunSplits.ANY_PERCENT.size());
        assertEquals("minecraft:story/enter_the_nether", SpeedrunSplits.ANY_PERCENT.get(1).advancement());
        assertEquals("minecraft:end/kill_dragon", SpeedrunSplits.ANY_PERCENT.getLast().advancement(), "Free the End");
    }

    @Test
    void theTimeReadsAsATimerDoes() {
        assertEquals("0:00.00", SpeedrunSplits.time(0));
        assertEquals("0:01.05", SpeedrunSplits.time(21));
        assertEquals("4:12.30", SpeedrunSplits.time(20 * 252 + 6));
        assertEquals("1:02:03.45", SpeedrunSplits.time((3600 + 123) * 20 + 9));
    }
}
