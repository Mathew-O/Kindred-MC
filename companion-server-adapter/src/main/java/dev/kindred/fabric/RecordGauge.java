package dev.kindred.fabric;

import dev.kindred.persistence.SqliteCompanionStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: a gauge of what the World Record, the knowledge webs, the Wire and the Chronicle hold, logged once a
 * game day and at a stop, so a long run shows whether anything grows without end.
 *
 * Technical: The month-long cost check. The Record's counts are the store's own ({@link SqliteCompanionStore#growth}):
 * every event written there since it opened, by kind, with its data's encoded length, the journal's and the action
 * ledger's included, which the store writes in their own rows' transactions and never pass the runtime. The database's
 * size on disk is read off the server thread; the in-memory sizes are given by the runtime; the far looks (F-35m's sight
 * lines to far columns' tops) are counted where they are cast, on the server thread. One log line a game day:
 * {@code Kindred cost: ...}. Nothing here changes what is kept.
 */
final class RecordGauge {
    /** What the runtime holds in memory now. */
    record Held(long recordThrough, int webs, long webRelations, int worldRelations, long inboxed, int chroniclePages, long teamStateTold) { }

    /** The day's line from the store's {@code growth}, the database's files measured off the thread first. */
    static CompletableFuture<String> line(long day, Path database, Held held, SqliteCompanionStore.Growth growth) {
        var counts = new TreeMap<String, String>();
        growth.byKind().forEach((k, v) -> counts.put(k, v[0] + "/" + v[1]));
        long w = growth.rows(), c = growth.characters();
        // Far columns asked since start (F-35m), read here on the server thread that counts them.
        long looks = WorldKnowledge.Looks.calls, rays = WorldKnowledge.Looks.raysCast, lookNanos = WorldKnowledge.Looks.nanos;
        return CompletableFuture.supplyAsync(() -> {
            long bytes = size(database) + size(database.resolveSibling(database.getFileName() + "-wal"));
            Runtime rt = Runtime.getRuntime();
            long heap = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            return "Kindred cost: day " + day + ": the Record through event " + held.recordThrough() + ", " + w + " written since start (" + c
                    + " characters of data); by kind events/characters " + counts + "; the database " + bytes + " bytes on disk; webs " + held.webs()
                    + " holding " + held.webRelations() + " relations, the world's " + held.worldRelations() + "; Wire inboxes " + held.inboxed()
                    + " messages; teammates' updates (team.state) " + held.teamStateTold() + " told since start, kept only in the peer ledgers"
                    + "; Chronicle " + held.chroniclePages() + " pages kept; heap in use " + heap + " MB"
                    + "; far looks " + looks + " since start, " + rays + " sight lines, " + lookNanos / 1_000_000 + " ms";
        });
    }

    private static long size(Path p) {
        try {
            return Files.exists(p) ? Files.size(p) : 0;
        } catch (java.io.IOException e) {
            return -1;
        }
    }
}
