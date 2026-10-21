package dev.kindred.core.attention;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AttentionWorkSchedulerTest {
    private final UUID session = UUID.randomUUID();
    private final UUID player1 = UUID.randomUUID();
    private final UUID player2 = UUID.randomUUID();
    private final UUID player3 = UUID.randomUUID();

    @Test
    void threePlayersShareBudgetWithRoundRobinFairness() {
        // Soft time limit must not trip on slow CI hosts while asserting four consecutive starts.
        var policy = new AttentionSchedulePolicy(6, 2, 60_000_000L, false);
        var scheduler = new AttentionWorkScheduler(session, policy);
        long tick = 100;
        scheduler.synchronizeEntries(List.of(
                new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 1, 1, AttentionState.ROUTINE, true),
                new AttentionWorkScheduler.PlayerEntrySnapshot(player2, tick, 1, 1, AttentionState.ROUTINE, true),
                new AttentionWorkScheduler.PlayerEntrySnapshot(player3, tick, 1, 1, AttentionState.ROUTINE, true)
        ));

        long tickStart = System.nanoTime();
        scheduler.beginTick(tick, tickStart);

        // First 3 selections must be from 3 distinct players (round-robin)
        var t1 = scheduler.selectNext().orElseThrow();
        assertTrue(scheduler.tryStart(t1, tickStart));

        var t2 = scheduler.selectNext().orElseThrow();
        assertTrue(scheduler.tryStart(t2, tickStart));

        var t3 = scheduler.selectNext().orElseThrow();
        assertTrue(scheduler.tryStart(t3, tickStart));

        // All 3 players served
        var served = java.util.Set.of(t1.playerId(), t2.playerId(), t3.playerId());
        assertEquals(3, served.size(), "Each player must receive a turn before any player receives a second");

        // Next turns also round-robin
        var t4 = scheduler.selectNext().orElseThrow();
        assertTrue(scheduler.tryStart(t4, tickStart));
        assertEquals(t1.playerId(), t4.playerId(), "Turn must cycle back to first player");
    }

    @Test
    void dirtyEventStormCoalescesAndRetainsDueAge() {
        var scheduler = new AttentionWorkScheduler(session, AttentionSchedulePolicy.defaultPolicy());
        long tick = 100;
        scheduler.synchronizeEntries(List.of(
                new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 1, 1, AttentionState.ROUTINE, true)
        ));

        // Rapid fire 20 invalidations on ROUTINE_GOALS
        for (int i = 0; i < 20; i++) {
            scheduler.invalidate(player1, WorkKind.ROUTINE_GOALS);
        }

        var diag = scheduler.diagnostics();
        assertEquals(20L, diag.invalidationsByKind().get(WorkKind.ROUTINE_GOALS));

        long now = System.nanoTime(); // One clock reading: a slow machine must not spend the tick's soft time budget.
        scheduler.beginTick(tick, now);
        WorkToken token = null;
        while (true) {
            var opt = scheduler.selectNext();
            if (opt.isEmpty()) break;
            var t = opt.get();
            if (t.kind() == WorkKind.ROUTINE_GOALS) {
                token = t;
                break;
            }
            assertTrue(scheduler.tryStart(t, now));
        }

        assertNotNull(token, "ROUTINE_GOALS must be scheduled");
        assertEquals(player1, token.playerId());
        assertEquals(20L, token.invalidationRevision(), "Invalidation revision must reflect the latest storm count");

        // Exactly one slice issued, not 20!
        assertTrue(scheduler.tryStart(token, now));
    }

    @Test
    void singleUseTokensAndInvalidatedRunningTokenRejected() {
        var scheduler = new AttentionWorkScheduler(session, AttentionSchedulePolicy.defaultPolicy());
        long tick = 50;
        scheduler.synchronizeEntries(List.of(
                new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 1, 1, AttentionState.ROUTINE, true)
        ));
        long now = System.nanoTime(); // One clock reading: a slow machine must not spend the tick's soft time budget.
        scheduler.beginTick(tick, now);

        var token = scheduler.selectNext().orElseThrow();

        // 1. Cannot start twice
        assertTrue(scheduler.tryStart(token, now));
        assertFalse(scheduler.tryStart(token, now), "Duplicate tryStart must fail");

        // 2. Invalidation while in flight supersedes token
        scheduler.invalidate(player1, token.kind());

        // 3. Completing superseded token must fail
        assertFalse(scheduler.complete(token), "Superseded token completion must be rejected");
        assertTrue(scheduler.diagnostics().staleTokenRejections() > 0);
    }

    @Test
    void expiredAndStaleGenerationTokenRejected() {
        var scheduler = new AttentionWorkScheduler(session, AttentionSchedulePolicy.defaultPolicy());
        long tick = 50;
        scheduler.synchronizeEntries(List.of(
                new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 1, 1, AttentionState.ROUTINE, true)
        ));
        scheduler.beginTick(tick, System.nanoTime());

        var token = scheduler.selectNext().orElseThrow();

        // Advance tick -> token is expired
        scheduler.beginTick(tick + 1, System.nanoTime());
        assertFalse(scheduler.tryStart(token, System.nanoTime()), "Expired token must be rejected");

        // Body generation change invalidates token
        var scheduler2 = new AttentionWorkScheduler(session, AttentionSchedulePolicy.defaultPolicy());
        scheduler2.synchronizeEntries(List.of(
                new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 1, 1, AttentionState.ROUTINE, true)
        ));
        scheduler2.beginTick(tick, System.nanoTime());
        var token2 = scheduler2.selectNext().orElseThrow();

        // New generation
        scheduler2.synchronizeEntries(List.of(
                new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 2, 2, AttentionState.ALERT, true)
        ));
        assertFalse(scheduler2.tryStart(token2, System.nanoTime()), "Token with stale body generation must be rejected");
    }

    @Test
    void deterministicSliceAndDetailedScanBudgetCaps() {
        var scheduler = new AttentionWorkScheduler(session, AttentionSchedulePolicy.defaultPolicy());
        long tick = 100;
        scheduler.synchronizeEntries(List.of(
                new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 1, 1, AttentionState.ROUTINE, true),
                new AttentionWorkScheduler.PlayerEntrySnapshot(player2, tick, 1, 1, AttentionState.ROUTINE, true)
        ));
        scheduler.beginTick(tick, System.nanoTime());

        // Default policy allows at most 6 slices per tick
        int started = 0;
        while (true) {
            var opt = scheduler.selectNext();
            if (opt.isEmpty()) break;
            if (scheduler.tryStart(opt.get(), System.nanoTime())) {
                started++;
            }
        }
        assertEquals(6, started, "Exactly 6 slices permitted under deterministic limit");
        assertEquals(6, scheduler.slicesStartedThisTick());
    }

    @Test
    void lowOrZeroBudgetAllowsNoOptionalSlices() {
        var zeroPolicy = new AttentionSchedulePolicy(0, 0, 0, false);
        var scheduler = new AttentionWorkScheduler(session, zeroPolicy);
        long tick = 100;
        scheduler.synchronizeEntries(List.of(
                new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 1, 1, AttentionState.ROUTINE, true)
        ));
        scheduler.beginTick(tick, System.nanoTime());

        assertTrue(scheduler.selectNext().isEmpty(), "Zero budget must yield no optional work slices");
    }

    @Test
    void batchValidationRejectsDuplicateIdsAndCapacityOverflow() {
        var scheduler = new AttentionWorkScheduler(session, AttentionSchedulePolicy.defaultPolicy());
        long tick = 10;

        // Duplicate IDs in batch
        assertThrows(IllegalArgumentException.class, () ->
                scheduler.synchronizeEntries(List.of(
                        new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 1, 1, AttentionState.ROUTINE, true),
                        new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 1, 1, AttentionState.ROUTINE, true)
                )));

        // 4 players (exceeds 3)
        UUID player4 = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                scheduler.synchronizeEntries(List.of(
                        new AttentionWorkScheduler.PlayerEntrySnapshot(player1, tick, 1, 1, AttentionState.ROUTINE, true),
                        new AttentionWorkScheduler.PlayerEntrySnapshot(player2, tick, 1, 1, AttentionState.ROUTINE, true),
                        new AttentionWorkScheduler.PlayerEntrySnapshot(player3, tick, 1, 1, AttentionState.ROUTINE, true),
                        new AttentionWorkScheduler.PlayerEntrySnapshot(player4, tick, 1, 1, AttentionState.ROUTINE, true)
                )));
    }
}
