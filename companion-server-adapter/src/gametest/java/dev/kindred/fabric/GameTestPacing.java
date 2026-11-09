package dev.kindred.fabric;

import dev.kindred.persistence.SqliteCompanionStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.test.TestContext;
import net.minecraft.test.TestServer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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
    /** The tests that asked for a live server's pace, while they run. */
    private static final Set<TestContext> LIVE = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private long started;
    /**
     * A live server's simulation distance, its default in {@code server.properties}. The test server sets none, and with
     * its player manager's 0 only the chunks next to a player tick. Sand dug two chunks from it hung in the air, and a
     * climb that crossed a chunk edge waited for it till its test ended. A villager stopped by a chunk's edge, and a
     * minecart rolling out of the player's chunk stopped dead. Each such fixture held its ground simulated by hand
     * ({@link FixtureGround#simulated}), and one that did not failed whenever the grid put an edge in its way (F-22c26).
     */
    static final int SIMULATION_DISTANCE = 10;

    /**
     * The server kept at a live server's pace, 50 ms a tick, from now until {@code context}'s test ends: for a test that
     * weighs something timed by the clock (a line's typing delay) against work counted in ticks. The tests of its batch
     * run at that pace too (F-22c11).
     */
    static void live(TestContext context) {
        if (LIVE.add(context)) FixtureGround.whenDone(context, () -> LIVE.remove(context));
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!(server instanceof TestServer)) return;
            server.getPlayerManager().setViewDistance(SIMULATION_DISTANCE);
            server.getPlayerManager().setSimulationDistance(SIMULATION_DISTANCE);
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (server instanceof TestServer) started = System.nanoTime();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!(server instanceof TestServer)) return;
            long remaining, least = LIVE.isEmpty() ? MIN_TICK_NANOS : LIVE_TICK_NANOS;
            while ((remaining = started + least - System.nanoTime()) > 0 && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(remaining);
            }
            while (SqliteCompanionStore.workInFlight() > 0 && System.nanoTime() - started < LIVE_TICK_NANOS
                    && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(POLL_NANOS);
            }
        });
    }
}
