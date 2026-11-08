package dev.kindred.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.kindred.core.rescue.RescueRecipes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In plain terms: where a server keeps the ways out its players have learned, in one file every player shares, with the
 * reviewed ones shipped in the mod as a starting set.
 *
 * Technical: F-29d. {@code <world>/kindred/rescue-recipes.json}, read once at start; when the server has none, the starter
 * set from the mod's resources ({@code data/kindred/rescue-recipes.json}) is read instead. Written whole, off the server
 * thread, whenever the book's revision moves, so IO never blocks a tick.
 */
final class RescueRecipeStore implements AutoCloseable {
    /** The shipped starter set, in the mod's resources. */
    static final String STARTER = "/data/kindred/rescue-recipes.json";

    /** One recipe as the file has it. */
    static final class Entry {
        String signature;
        List<String> steps;
        int weight = 1;
        int failures;
        String source = "server";
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kindred-rescue-recipes");
        thread.setDaemon(true);
        return thread;
    });
    private long written = -1;

    RescueRecipeStore(Path worldKindredFolder) {
        this.file = worldKindredFolder.resolve("rescue-recipes.json");
    }

    /** The server's book: its own file if it has one, else the starter set, else empty. Unreadable entries are left out. */
    RescueRecipes load() {
        List<Entry> entries = null;
        try {
            if (Files.isRegularFile(file)) entries = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Entry>>() { }.getType());
        } catch (IOException | RuntimeException unreadable) {
            KindredMod.LOGGER.warn("Rescue recipes at {} could not be read ({}); starting from the starter set", file, unreadable.toString());
        }
        if (entries == null) entries = starter();
        var recipes = new ArrayList<RescueRecipes.Recipe>();
        for (Entry e : entries == null ? List.<Entry>of() : entries) {
            try {
                recipes.add(new RescueRecipes.Recipe(e.signature, e.steps, Math.max(1, e.weight), Math.max(0, e.failures), e.source == null ? "server" : e.source));
            } catch (RuntimeException bad) {
                // A malformed entry is left out.
            }
        }
        var book = new RescueRecipes(recipes);
        written = book.revision();
        KindredMod.LOGGER.info("Rescue recipes: {} kept", book.all().size());
        return book;
    }

    private List<Entry> starter() {
        try (InputStream in = RescueRecipeStore.class.getResourceAsStream(STARTER)) {
            if (in == null) return List.of();
            return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<Entry>>() { }.getType());
        } catch (IOException | RuntimeException unreadable) {
            KindredMod.LOGGER.warn("The starter rescue recipes could not be read: {}", unreadable.toString());
            return List.of();
        }
    }

    /** The book written, off the server thread, if it changed since it was last written. */
    void saveIfChanged(RescueRecipes book) {
        if (book.revision() == written) return;
        written = book.revision();
        var entries = new ArrayList<Entry>();
        for (var r : book.all()) {
            var e = new Entry();
            e.signature = r.signature();
            e.steps = r.steps();
            e.weight = r.weight();
            e.failures = r.failures();
            e.source = r.source();
            entries.add(e);
        }
        String json = gson.toJson(entries);
        io.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException plain) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failed) {
                KindredMod.LOGGER.warn("Rescue recipes could not be written: {}", failed.toString());
            }
        });
    }

    @Override
    public void close() {
        io.shutdown();
    }
}
