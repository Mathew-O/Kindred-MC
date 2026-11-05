package dev.kindred.fabric;

import dev.kindred.persistence.SqliteCompanionStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.test.TestServer;

import java.util.concurrent.locks.LockSupport;

/**
 * In plain terms: lets background saving catch up during GameTests, which otherwise run as fast as they can: a tick lasts
 * until the saving it is waiting on is done, up to a live server's tick.
 *
 * Technical: Gives asynchronous fixture IO time to run on the otherwise unpaced GameTest server. A tick lasts at least
 * 5 ms, and on until every metadata store's queued work is done ({@link SqliteCompanionStore#workInFlight()}), up to a
 * live server's 50 ms. A player's skill acts only once its paid slice and action record are durably written. On a live
 * server a write shorter than a tick costs the player at most that tick; on the test server's bare 5 ms, one slow write on
 * CI (about 0.2 s) was forty ticks. A clutch began after the landing (run 388), a stair stood still 32 ticks on its step
 * (run 380), and a hideout's 800 recorded blocks ran past its tick limit (run 391), on the builds whose writes were slow.
 * Each tick now gives a write the time a live server's would (F-22c5).
 */
public final class GameTestPacing implements ModInitializer {
    // Ten times faster than normal 20 TPS, while avoiding hundreds of ticks in a few milliseconds.
    private static final long MIN_TICK_NANOS = 5_000_000L;
    /** A live server's tick: the longest a tick waits for the stores' work. */
    private static final long LIVE_TICK_NANOS = 50_000_000L;
    /** How often a tick held for the stores looks again. */
    private static final long POLL_NANOS = 100_000L;
    private long started;

    @Override
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (server instanceof TestServer) started = System.nanoTime();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!(server instanceof TestServer)) return;
            long remaining;
            while ((remaining = started + MIN_TICK_NANOS - System.nanoTime()) > 0 && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(remaining);
            }
            while (SqliteCompanionStore.workInFlight() > 0 && System.nanoTime() - started < LIVE_TICK_NANOS
                    && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(POLL_NANOS);
            }
        });
    }
}
