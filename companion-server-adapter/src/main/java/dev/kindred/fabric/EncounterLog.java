package dev.kindred.fabric;

import dev.kindred.core.tactics.EncounterRecorder;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fight records written off the server thread (E-41a): a file a fight, {@code kindred/encounters/<player>/<encounter>.jsonl}
 * under the server's directory, and only the newest {@link #KEPT} of a player's kept. The lines are built on the server
 * thread from the finished record; only the writing waits on the disk.
 */
final class EncounterLog {
    static final int KEPT = 50;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "kindred-encounters");
        thread.setDaemon(true);
        return thread;
    });

    private EncounterLog() { }

    static Path directory(MinecraftServer server, UUID player) {
        return server.getRunDirectory().resolve("kindred").resolve("encounters").resolve(player.toString());
    }

    static CompletableFuture<Path> write(MinecraftServer server, UUID player, EncounterRecorder recorder) {
        List<String> lines = recorder.jsonLines();
        Path directory = directory(server, player);
        String name = recorder.encounterId() + ".jsonl";
        var written = CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(directory);
                Path file = directory.resolve(name);
                Files.write(file, lines, StandardCharsets.UTF_8);
                prune(directory);
                return file;
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }, WRITER);
        written.whenComplete((file, failure) -> {
            if (failure != null) KindredMod.LOGGER.warn("An encounter record could not be written to {}", directory, failure);
        });
        return written;
    }

    private static void prune(Path directory) throws IOException {
        var files = new ArrayList<Path>();
        try (var listing = Files.list(directory)) {
            listing.filter(path -> path.getFileName().toString().endsWith(".jsonl")).forEach(files::add);
        }
        if (files.size() <= KEPT) return;
        files.sort(Comparator.comparingLong(path -> path.toFile().lastModified()));
        for (int i = 0; i < files.size() - KEPT; i++) Files.deleteIfExists(files.get(i));
    }
}
