package dev.kindred.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.test.TestServer;

import java.util.concurrent.locks.LockSupport;

/**
 * In plain terms: lets background saving catch up during GameTests, which otherwise run as fast as they can.
 *
 * Technical: Gives asynchronous fixture IO time to run on the otherwise unpaced GameTest server.
 */
public final class GameTestPacing implements ModInitializer {
    // Ten times faster than normal 20 TPS, while avoiding hundreds of ticks in a few milliseconds.
    private static final long MIN_TICK_NANOS = 5_000_000L;
    private long deadline;

    @Override
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (server instanceof TestServer) deadline = System.nanoTime() + MIN_TICK_NANOS;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!(server instanceof TestServer)) return;
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0 && !Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(remaining);
            }
        });
    }
}
