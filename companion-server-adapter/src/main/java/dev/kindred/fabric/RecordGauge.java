package dev.kindred.fabric;

import dev.kindred.core.record.EventData;
import dev.kindred.core.record.WorldEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: a gauge of what the World Record, the knowledge webs, the Wire and the Chronicle hold, logged once a
 * game day and at a stop, so a long run shows whether anything grows without end.
 *
 * Technical: The month-long cost check. Counts each event the Record writes, by kind, with its data's encoded length;
 * the database's size on disk is read off the server thread; the in-memory sizes are given by the runtime. One log
 * line a game day: {@code Kindred cost: ...}. Nothing here changes what is kept.
 */
final class RecordGauge {
    private final Map<String, long[]> byKind = new TreeMap<>();
    private long written, chars;

    /** An event the Record has written. */
    void written(WorldEvent e) {
        written++;
        long n = EventData.encode(e.data()).length() + e.subject().length() + e.place().length();
        chars += n;
        var k = byKind.computeIfAbsent(e.kind(), x -> new long[2]);
        k[0]++;
        k[1] += n;
    }

    /** What the runtime holds in memory now. */
    record Held(long recordThrough, int webs, long webRelations, int worldRelations, long inboxed, int chroniclePages) { }

    /** The day's line, the database's files measured off the thread first. */
    CompletableFuture<String> line(long day, Path database, Held held) {
        var counts = new TreeMap<String, String>();
        byKind.forEach((k, v) -> counts.put(k, v[0] + "/" + v[1]));
        long w = written, c = chars;
        return CompletableFuture.supplyAsync(() -> {
            long bytes = size(database) + size(database.resolveSibling(database.getFileName() + "-wal"));
            Runtime rt = Runtime.getRuntime();
            long heap = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            return "Kindred cost: day " + day + ": the Record through event " + held.recordThrough() + ", " + w + " written since start (" + c
                    + " characters of data); by kind events/characters " + counts + "; the database " + bytes + " bytes on disk; webs " + held.webs()
                    + " holding " + held.webRelations() + " relations, the world's " + held.worldRelations() + "; Wire inboxes " + held.inboxed()
                    + " messages; Chronicle " + held.chroniclePages() + " pages kept; heap in use " + heap + " MB";
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
