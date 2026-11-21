package dev.kindred.fabric;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.MinecraftServer;

/**
 * In plain terms: when the whole server stalls for seconds at a time, a line in the log says how long it stalled and
 * what it was doing meanwhile: collecting garbage, loading or generating chunks, or saving the world. It changes
 * nothing the server does.
 *
 * Technical: a diagnostic for F-22c101, log only. Each span of server time is measured from one mark to the next: a
 * tick's own length (from its start to its end) and the time between ticks (from one tick's end to the next one's
 * start). A span of at least {@link #PAUSE_NANOS} logs one line with its length, the garbage collections run in it (count
 * and time, from the JVM's own collector beans), the chunks loaded and generated in it, and the world saves in it. Every
 * other span logs nothing. Counted on the server thread, except chunk events, which are counted atomically.
 */
public final class TickPauseLog {
    /** A span this long or longer is logged. */
    static final long PAUSE_NANOS = 4_000_000_000L;

    private static final List<GarbageCollectorMXBean> COLLECTORS = ManagementFactory.getGarbageCollectorMXBeans();
    private static final AtomicInteger LOADED = new AtomicInteger(), GENERATED = new AtomicInteger();
    private static long mark = -1, markGcCount, markGcMillis;
    private static int saves;
    private static long saveNanos, saveStart = -1;

    private TickPauseLog() { }

    /** Counts chunks loaded and generated (once, as the mod initialises). */
    static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> LOADED.incrementAndGet());
        ServerChunkEvents.CHUNK_GENERATE.register((world, chunk) -> GENERATED.incrementAndGet());
    }

    /** As a tick starts: the time since the last tick ended, if long, is logged. */
    static void tickStarted(MinecraftServer server) { span(server, "between ticks"); }

    /** As a tick ends: the tick's own length, if long, is logged. */
    static void tickEnded(MinecraftServer server) { span(server, "in tick " + server.getTicks()); }

    /** A world save begins (from {@code MinecraftServer.saveAll}). */
    public static void saveStarted() { saveStart = System.nanoTime(); }

    /** A world save ends. */
    public static void saveEnded() {
        if (saveStart < 0) return;
        saves++;
        saveNanos += System.nanoTime() - saveStart;
        saveStart = -1;
    }

    private static void span(MinecraftServer server, String where) {
        long now = System.nanoTime(), count = 0, millis = 0;
        for (var collector : COLLECTORS) {
            count += Math.max(0, collector.getCollectionCount());
            millis += Math.max(0, collector.getCollectionTime());
        }
        int loaded = LOADED.getAndSet(0), generated = GENERATED.getAndSet(0);
        if (mark >= 0 && now - mark >= PAUSE_NANOS) {
            KindredMod.LOGGER.info("Server pause (F-22c101): {} ms {}; garbage collection {} runs, {} ms; chunks {} loaded, {} generated;"
                            + " world saves {} ({} ms); players {}", (now - mark) / 1_000_000, where, count - markGcCount, millis - markGcMillis,
                    loaded, generated, saves, saveNanos / 1_000_000, server.getCurrentPlayerCount());
        }
        mark = now;
        markGcCount = count;
        markGcMillis = millis;
        saves = 0;
        saveNanos = 0;
    }
}
