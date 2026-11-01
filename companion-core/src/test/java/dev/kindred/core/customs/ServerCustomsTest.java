package dev.kindred.core.customs;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: the server's customs are learned only from those who opted in, put together, kept within their
 * bound, and given back whole when a person opts out.
 *
 * Technical: E-47 (D10): the aggregation, the bounds and the deletion.
 */
class ServerCustomsTest {
    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes()), BEN = UUID.nameUUIDFromBytes("ben".getBytes());

    private static void mine(ServerCustoms customs, UUID who, ServerCustoms.Ore ore, int y, int times) {
        for (int i = 0; i < times; i++) customs.mined(who, ore, y);
    }

    @Test
    void nothingIsLearnedFromAnyoneWhoHasNotOptedIn() {
        var customs = new ServerCustoms();
        mine(customs, BEN, ServerCustoms.Ore.IRON, 12, 40);
        assertEquals(0, customs.minedCount(ServerCustoms.Ore.IRON));
        assertTrue(customs.miningHeight(ServerCustoms.Ore.IRON).isEmpty());
        assertEquals(16, customs.blend(ServerCustoms.Ore.IRON, 16), "the prior, untouched");
        assertFalse(customs.consents(BEN));
    }

    @Test
    void theCustomIsTheMedianOfWhatEveryoneConsentingMinedOnceThereIsEnough() {
        var customs = new ServerCustoms();
        customs.optIn(ANNA);
        customs.optIn(BEN);
        mine(customs, ANNA, ServerCustoms.Ore.IRON, 12, ServerCustoms.MIN_MINED - 1);
        assertTrue(customs.miningHeight(ServerCustoms.Ore.IRON).isEmpty(), "too few yet");
        mine(customs, BEN, ServerCustoms.Ore.IRON, 13, 1);
        assertEquals(14, customs.miningHeight(ServerCustoms.Ore.IRON).getAsInt(), "y 12 and 13 share the bucket from 12 to 15");
        mine(customs, BEN, ServerCustoms.Ore.IRON, -40, 30);
        assertEquals(-38, customs.miningHeight(ServerCustoms.Ore.IRON).getAsInt(), "the median moves with the most mined");
        assertTrue(customs.miningHeight(ServerCustoms.Ore.DIAMOND).isEmpty(), "each ore its own");
        mine(customs, ANNA, ServerCustoms.Ore.IRON, 400, 5);
        assertEquals(46, customs.minedCount(ServerCustoms.Ore.IRON), "a height outside the world is not counted");
    }

    @Test
    void theBlendMovesThePriorAtMostThreeTenthsOfTheWayAndEightBlocks() {
        var customs = new ServerCustoms();
        customs.optIn(ANNA);
        mine(customs, ANNA, ServerCustoms.Ore.IRON, 12, 20);
        // The custom is 14: three tenths of the way from 16 is 15.4, so 15.
        assertEquals(15, customs.blend(ServerCustoms.Ore.IRON, 16));
        mine(customs, ANNA, ServerCustoms.Ore.DIAMOND, -40, 20);
        assertEquals(-52, customs.blend(ServerCustoms.Ore.DIAMOND, -58), "the custom -38, twenty blocks off: moved six");
        var far = new ServerCustoms();
        far.optIn(ANNA);
        mine(far, ANNA, ServerCustoms.Ore.IRON, 200, 20);
        assertEquals(16 + ServerCustoms.MAX_SHIFT, far.blend(ServerCustoms.Ore.IRON, 16), "never more than eight blocks");
        mine(far, ANNA, ServerCustoms.Ore.COAL, -60, 20);
        assertEquals(48 - ServerCustoms.MAX_SHIFT, far.blend(ServerCustoms.Ore.COAL, 48), "either way");
    }

    @Test
    void optingOutDeletesEverythingLearnedFromThatPersonAndNothingElse() {
        var customs = new ServerCustoms();
        customs.optIn(ANNA);
        customs.optIn(BEN);
        mine(customs, ANNA, ServerCustoms.Ore.IRON, 12, 20);
        mine(customs, BEN, ServerCustoms.Ore.IRON, -40, 20);
        assertTrue(customs.optOut(ANNA));
        assertFalse(customs.consents(ANNA));
        assertEquals(20, customs.minedCount(ServerCustoms.Ore.IRON), "Ben's twenty remain");
        assertEquals(-38, customs.miningHeight(ServerCustoms.Ore.IRON).getAsInt());
        assertFalse(customs.snapshot().mined().containsKey(ANNA), "nothing of Anna's kept");
        mine(customs, ANNA, ServerCustoms.Ore.IRON, 12, 20);
        assertEquals(20, customs.minedCount(ServerCustoms.Ore.IRON), "nor learned again once out");
        assertTrue(customs.optOut(BEN));
        assertEquals(16, customs.blend(ServerCustoms.Ore.IRON, 16), "with no one left, the prior");
        assertFalse(customs.optOut(BEN), "nothing left to delete");
    }

    @Test
    void aSnapshotKeepsOnlyConsentingPeoplesCounts() {
        var customs = new ServerCustoms();
        customs.optIn(ANNA);
        mine(customs, ANNA, ServerCustoms.Ore.IRON, 12, 20);
        var again = ServerCustoms.of(customs.snapshot());
        assertTrue(again.consents(ANNA));
        assertEquals(15, again.blend(ServerCustoms.Ore.IRON, 16));
        // A saved file with counts of someone not consenting, as a tampered or an old file might be: left out.
        var stray = new java.util.HashMap<>(customs.snapshot().mined());
        stray.put(BEN, java.util.Map.of(ServerCustoms.Ore.IRON, new int[ServerCustoms.BUCKETS]));
        var loaded = ServerCustoms.of(new ServerCustoms.Snapshot(java.util.List.of(ANNA), stray));
        assertFalse(loaded.snapshot().mined().containsKey(BEN));
        assertEquals(0, ServerCustoms.of(new ServerCustoms.Snapshot(null, null)).minedCount(ServerCustoms.Ore.IRON), "an empty file");
    }
}
