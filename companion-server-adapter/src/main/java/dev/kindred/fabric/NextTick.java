package dev.kindred.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * In plain terms: runs a bit of work at the end of the next game tick, for waits that should not keep the
 * server busy in between.
 *
 * Technical: Work for the end of the next server tick, for a wait that must not spin between ticks (a queued
 * server task runs at once whenever the tick has time to spare). Server thread only.
 */
final class NextTick {
    private static final List<Runnable> due = new ArrayList<>();
    private static boolean registered;

    private NextTick() { }

    /** Runs the task at the end of the next tick. */
    static void run(Runnable task) {
        if (!registered) {
            registered = true;
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                if (due.isEmpty()) return;
                var now = new ArrayList<>(due);
                due.clear();
                now.forEach(Runnable::run);
            });
        }
        due.add(task);
    }
}
