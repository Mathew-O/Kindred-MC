package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the dibs list: two players can never claim the same block or chest, claims run out,
 * and a newer claim wins.
 */
class ClaimRegistryTest {
    private final ClaimRegistry registry = new ClaimRegistry();
    private final UUID epoch = UUID.randomUUID();
    private final UUID taskA = UUID.randomUUID();
    private final UUID taskB = UUID.randomUUID();

    @Test
    void competingOwnersNeverHoldTheSameKeyAndAcquisitionIsAllOrNothing() {
        var a = registry.acquire(taskA, epoch, List.of("block:1,64,1", "chest:2,64,1"), 0, 30_000).orElseThrow();
        assertTrue(registry.acquire(taskB, epoch, List.of("block:9,64,9", "chest:2,64,1"), 1_000, 30_000).isEmpty(),
                "one contested key denies the whole request");
        assertTrue(registry.holderOf("block:9,64,9", 1_000).isEmpty(), "the uncontested key was not taken either");
        assertTrue(registry.holdsAll(a, 29_999));
        assertFalse(registry.holdsAll(a, 30_000));
        var b = registry.acquire(taskB, epoch, List.of("chest:2,64,1"), 30_000, 30_000).orElseThrow();
        assertTrue(b.token() > a.token(), "fencing tokens increase monotonically");
        assertEquals(taskB, registry.holderOf("chest:2,64,1", 30_001).orElseThrow());
        // The expired owner can neither renew nor release the replacement claim.
        assertTrue(registry.renew(a, 31_000, 30_000).isEmpty());
        assertEquals(1, registry.release(a), "only its own still-held key is released");
        assertEquals(taskB, registry.holderOf("chest:2,64,1", 31_000).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> registry.acquire(taskA, epoch, List.of(), 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> registry.acquire(taskA, epoch, List.of("x"), 0, ClaimRegistry.MAX_TTL_MILLIS + 1));
    }

    @Test
    void renewalKeepsTheTokenAndStopReleasesEverythingRegardlessOfExpiry() {
        var grant = registry.acquire(taskA, epoch, List.of("region:0,0"), 0, 10_000).orElseThrow();
        var renewed = registry.renew(grant, 9_000, 10_000).orElseThrow();
        assertEquals(grant.token(), renewed.token());
        assertEquals(19_000, renewed.expiresAt());
        assertTrue(registry.holdsAll(renewed, 18_999));
        assertFalse(registry.holdsAll(renewed, 19_000));
        registry.acquire(taskA, epoch, List.of("block:5,5,5"), 10_000, 10_000).orElseThrow();
        assertEquals(2, registry.releaseTask(taskA));
        assertEquals(0, registry.size());
        assertTrue(registry.acquire(taskB, epoch, List.of("region:0,0"), 10_001, 10_000).isPresent());
    }

    @Test
    void restartDropsEveryClaimFromAnOlderEpochAndASameTaskReacquireReleases() {
        UUID oldEpoch = UUID.randomUUID();
        registry.acquire(taskA, oldEpoch, List.of("block:1,1,1"), 0, 60_000).orElseThrow();
        var current = registry.acquire(taskB, epoch, List.of("block:2,2,2"), 0, 60_000).orElseThrow();
        assertEquals(1, registry.invalidateOtherEpochs(epoch));
        assertTrue(registry.holderOf("block:1,1,1", 1).isEmpty(), "a stale lease is never restored as authority");
        assertTrue(registry.holdsAll(current, 1));
        var again = registry.acquire(taskB, epoch, List.of("block:2,2,2", "block:3,3,3"), 2, 60_000).orElseThrow();
        assertTrue(again.token() > current.token());
        assertFalse(registry.holdsAll(current, 3), "re-leasing under a new token supersedes the old grant");
    }

    @Test
    void releaseKeyReleasesOnlyTargetKeyForHoldingTask() {
        registry.acquire(taskA, epoch, List.of("block:1,1,1", "block:2,2,2"), 0, 30_000).orElseThrow();
        assertFalse(registry.releaseKey(taskB, "block:1,1,1"), "Wrong task cannot release key held by another");
        assertTrue(registry.releaseKey(taskA, "block:1,1,1"), "Holder task releases key");
        assertFalse(registry.releaseKey(taskA, "block:1,1,1"), "Already released key returns false");
        assertEquals(1, registry.size(), "Other key remains held");
        assertTrue(registry.holderOf("block:2,2,2", 0).isPresent());
        assertTrue(registry.holderOf("block:1,1,1", 0).isEmpty());
    }
}
