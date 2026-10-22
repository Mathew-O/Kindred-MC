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
 * What each AI player has learnt about each world by playing in it, kept across restarts: the places that matter on
 * the way to the End (the portals it went through, the fortress it found, where its eyes' flights cross, the End
 * portal) and each eye flight it watched. One small JSON file per player in the world's {@code kindred/memory} folder.
 * Only what the player saw itself is kept, as it saw it; a restart no longer sends it to throw eyes again, or to find
 * a fortress again that it already found. Reads and writes run on their own thread; a write replaces the file whole.
 */
final class PlayerMemoryStore implements AutoCloseable {
    /** A place in one dimension, by its id ("minecraft:the_nether"). */
    record Place(String dimension, int x, int y, int z) { }

    /** An eye's flight: thrown from x, z along dx, dz. */
    record Flight(double x, double z, double dx, double dz) { }

    /** One player's memory of one world. Mutated on the game thread only, copied for writing. */
    static final class Memory {
        String player = "";
        String id = "";
        Place overworldPortal, netherPortal, fortress, endPortal;
        List<Flight> flights = new ArrayList<>();
        /** Where the flights cross (x, z), and whether an eye thrown close by pinned it. */
        double[] stronghold;
        boolean pinned;
        /** It saw the Ender Dragon die (E-39). */
        boolean dragonKilled;
    }

    private final Path folder;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kindred-player-memory");
        thread.setDaemon(true);
        return thread;
    });

    PlayerMemoryStore(Path worldKindredFolder) {
        this.folder = worldKindredFolder.resolve("memory");
    }

    private Path file(UUID id) { return folder.resolve(id + ".json"); }

    /** This player's memory of this world, or an empty one; read off the game thread. */
    CompletableFuture<Memory> load(UUID id, String name) {
        return CompletableFuture.supplyAsync(() -> {
            Memory memory = null;
            try {
                if (Files.isRegularFile(file(id))) memory = gson.fromJson(Files.readString(file(id), StandardCharsets.UTF_8), Memory.class);
            } catch (IOException | RuntimeException unreadable) {
                KindredMod.LOGGER.warn("Memory of {} could not be read; starting afresh: {}", name, unreadable.toString());
            }
            if (memory == null) memory = new Memory();
            if (memory.flights == null) memory.flights = new ArrayList<>();
            memory.player = name;
            memory.id = id.toString();
            return memory;
        }, io);
    }

    /** Write a copy taken now on the game thread; the write itself happens off it. */
    void save(UUID id, Memory memory) {
        String json = gson.toJson(memory);
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
                KindredMod.LOGGER.warn("Memory of {} could not be written: {}", memory.player, failed.toString());
            }
        });
    }

    @Override
    public void close() {
        io.shutdown();
    }
}
