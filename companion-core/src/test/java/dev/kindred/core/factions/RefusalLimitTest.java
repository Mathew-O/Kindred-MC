package dev.kindred.core.factions;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.kindred.core.factions.RefusalLimit.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: a refused attempt is written once a minute for each player and chunk, and at most the cap a game day
 * for each faction's land; a griefer hammering a border writes no more than that, and the rest is only counted (F-34c2).
 */
class RefusalLimitTest {
    @Test
    void oncePerActorAndChunkAMinute() {
        var limit = new RefusalLimit(100);
        UUID griefer = UUID.randomUUID();
        assertEquals(WRITE, limit.admit(griefer, "w:0:0", "red", 0, 1));
        assertEquals(SAME_MINUTE, limit.admit(griefer, "w:0:0", "red", 59_999, 1));
        assertEquals(WRITE, limit.admit(griefer, "w:0:1", "red", 59_999, 1), "another chunk is its own");
        assertEquals(WRITE, limit.admit(UUID.randomUUID(), "w:0:0", "red", 59_999, 1), "another player is their own");
        assertEquals(WRITE, limit.admit(griefer, "w:0:0", "red", 60_000, 1), "a new minute");
        assertTrue(limit.sameMinute(griefer, "w:0:0", 60_001), "asked first, before any lookup");
        assertFalse(limit.sameMinute(griefer, "w:0:0", 120_000));
        assertFalse(limit.sameMinute(griefer, "w:5:5", 60_001), "a chunk never written");
        assertEquals(2, limit.counts()[1], "the yes counted as the same minute's");
    }

    @Test
    void aGrieferAtTheCapWritesNoMoreThanTheLimitsAllow() {
        int cap = 100;
        var limit = new RefusalLimit(cap);
        UUID griefer = UUID.randomUUID();
        // Twenty attempts a second for a game day (20 real minutes) along a border of five of Red's chunks.
        int writes = 0;
        for (long ms = 0; ms < 20 * RefusalLimit.MINUTE_MS; ms += 50) {
            if (limit.admit(griefer, "w:0:" + (ms / 50) % 5, "red", ms, 7) == WRITE) writes++;
        }
        assertEquals(100, writes, "five chunks once a minute is 100 in the game day: the cap reached, never passed");
        assertTrue(writes <= 5 * 20 && writes <= cap);
        var counts = limit.counts();
        assertEquals(100, counts[0]);
        assertEquals(24_000 - 100, counts[1] + counts[2], "every other attempt only counted");
        assertTrue(limit.held() <= 5, "the minute's map holds at most the pairs of the last minute: " + limit.held());
        // Past the cap: a new chunk the same day is counted, not written; the next game day starts again.
        var more = new RefusalLimit(cap);
        for (int i = 0; i < cap; i++) assertEquals(WRITE, more.admit(UUID.randomUUID(), "w:9:" + i, "red", i, 3));
        UUID late = UUID.randomUUID();
        assertEquals(FACTION_CAP, more.admit(late, "w:9:x", "red", cap, 3));
        assertTrue(more.sameMinute(late, "w:9:x", cap + 10), "over the cap, its repeats take the one-lookup path too");
        assertEquals(WRITE, more.admit(UUID.randomUUID(), "w:9:x", "blue", cap, 3), "another faction's cap is its own");
        assertEquals(WRITE, more.admit(UUID.randomUUID(), "w:9:y", "red", cap + 1, 4), "a new game day");
        assertEquals(1, more.counts()[2]);
    }
}
