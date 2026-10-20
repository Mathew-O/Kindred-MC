package dev.kindred.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The strategist's memory for each AI player in each world (F-19b): one small JSON file per player in the world's
 * {@code kindred/model-context} folder, holding its notes, its deaths, the advice it was given and what the
 * consultations cost. Each world keeps its own, so a player remembers this world's caves, not another's. Reads and
 * writes run on their own thread, never the game's; a write replaces the file whole, so a crash leaves the last
 * complete one. It holds only the player's own game events and the model's notes about them, nothing from people.
 */
final class ModelContextStore implements AutoCloseable {
    static final int MAX_NOTES = 20, MAX_DEATHS = 20, MAX_ADVICE = 20;

    /** One player's context in one world, as stored. Mutated on the game thread only, copied for writing. */
    static final class PlayerContext {
        String player = "";
        String id = "";
        List<Note> notes = new ArrayList<>();
        List<DeathRecord> deaths = new ArrayList<>();
        List<AdviceRecord> advice = new ArrayList<>();
        long consultations, inputTokens, outputTokens;

        List<String> noteTexts() { return notes.stream().map(note -> note.text).toList(); }

        void addNote(long tick, String text) {
            if (text == null || text.isBlank() || notes.stream().anyMatch(note -> note.text.equalsIgnoreCase(text))) return;
            notes.add(new Note(tick, text));
            while (notes.size() > MAX_NOTES) notes.removeFirst();
        }

        void addDeath(long tick, int x, int y, int z, String cause, String stage) {
            deaths.add(new DeathRecord(tick, x, y, z, cause, stage));
            while (deaths.size() > MAX_DEATHS) deaths.removeFirst();
        }

        void addAdvice(long tick, String stance, String reason) {
            advice.add(new AdviceRecord(tick, stance, reason));
            while (advice.size() > MAX_ADVICE) advice.removeFirst();
        }
    }

    record Note(long tick, String text) { }
    record DeathRecord(long tick, int x, int y, int z, String cause, String stage) { }
    record AdviceRecord(long tick, String stance, String reason) { }

    private final Path folder;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kindred-model-context");
        thread.setDaemon(true);
        return thread;
    });

    ModelContextStore(Path worldKindredFolder) {
        this.folder = worldKindredFolder.resolve("model-context");
    }

    private Path file(UUID id) { return folder.resolve(id + ".json"); }

    /** This player's context in this world, or a fresh one; read off the game thread. */
    CompletableFuture<PlayerContext> load(UUID id, String name) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerContext context = null;
            try {
                if (Files.isRegularFile(file(id))) context = gson.fromJson(Files.readString(file(id), StandardCharsets.UTF_8), PlayerContext.class);
            } catch (IOException | RuntimeException unreadable) {
                KindredMod.LOGGER.warn("Model context for {} could not be read; starting afresh: {}", name, unreadable.toString());
            }
            if (context == null) context = new PlayerContext();
            if (context.notes == null) context.notes = new ArrayList<>();
            if (context.deaths == null) context.deaths = new ArrayList<>();
            if (context.advice == null) context.advice = new ArrayList<>();
            context.player = name;
            context.id = id.toString();
            return context;
        }, io);
    }

    /** Write a copy taken now on the game thread; the write itself happens off it. */
    void save(UUID id, PlayerContext context) {
        String json = gson.toJson(context);
        io.execute(() -> {
            try {
                Files.createDirectories(folder);
                Path temp = folder.resolve(id + ".json.tmp");
                Files.writeString(temp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException plain) {
                    Files.move(temp, file(id), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failed) {
                KindredMod.LOGGER.warn("Model context for {} could not be written: {}", context.player, failed.toString());
            }
        });
    }

    @Override
    public void close() {
        io.shutdown();
    }
}
