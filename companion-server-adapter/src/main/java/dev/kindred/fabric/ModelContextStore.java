package dev.kindred.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.kindred.core.survival.PlanPreferences;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The strategist's memory for each AI player in each world (F-19b): one small JSON file per player in the world's
 * {@code kindred/model-context} folder, holding its notes, its deaths, the advice it was given and what the
 * consultations cost. A note may carry a habit the player keeps in this world (F-19c); the habits are read from the
 * notes, so removing a note from the file, or the file, removes its habit too. Each world keeps its own, so a player remembers this world's caves, not another's. Reads and
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

        /** The notes as the report shows them, each with the habit it carries. */
        List<String> noteTexts() {
            return notes.stream().map(note -> habitOf(note) == PlanPreferences.Habit.NONE ? note.text : "[habit " + habitOf(note) + "] " + note.text).toList();
        }

        /** The habits kept in this world: those the notes carry. */
        Set<PlanPreferences.Habit> habits() {
            var habits = EnumSet.noneOf(PlanPreferences.Habit.class);
            for (Note note : notes) habits.add(habitOf(note));
            habits.remove(PlanPreferences.Habit.NONE);
            return habits;
        }

        void addNote(long tick, String text) {
            addNote(tick, text, PlanPreferences.Habit.NONE);
        }

        /**
         * Keep a note, and the habit it teaches. A habit already kept stays with its first note; past
         * {@link PlanPreferences#MAX_HABITS} the oldest habit's note is kept as a plain note. A lesson already in
         * the notes, however it is worded ({@link #sameLesson}), is not kept twice: its first wording moves up to
         * the newest, so a lesson learned again outlasts one learned once (at the real-time pace live run 66 filled
         * all twenty notes with one lesson).
         */
        void addNote(long tick, String text, PlanPreferences.Habit habit) {
            if (text == null || text.isBlank()) return;
            boolean kept = habit == PlanPreferences.Habit.NONE || habits().contains(habit);
            int same = -1;
            for (int i = 0; i < notes.size() && same < 0; i++) if (sameLesson(notes.get(i).text, text)) same = i;
            Note first = same >= 0 ? notes.get(same) : null;
            // A habit learned now joins its lesson's first note, unless that note already teaches another.
            if (first != null && (kept || habitOf(first) == PlanPreferences.Habit.NONE)) {
                notes.remove(same);
                notes.add(new Note(tick, first.text, kept ? first.habit : habit.name()));
            } else notes.add(new Note(tick, text, kept ? null : habit.name()));
            while (habits().size() > PlanPreferences.MAX_HABITS) {
                for (int i = 0; i < notes.size(); i++) {
                    if (habitOf(notes.get(i)) != PlanPreferences.Habit.NONE) {
                        notes.set(i, new Note(notes.get(i).tick, notes.get(i).text, null));
                        break;
                    }
                }
            }
            // Old plain notes go first; a note that carries a habit goes only when there are too many of those.
            while (notes.size() > MAX_NOTES) {
                int plain = -1;
                for (int i = 0; i < notes.size() && plain < 0; i++) if (habitOf(notes.get(i)) == PlanPreferences.Habit.NONE) plain = i;
                notes.remove(plain >= 0 ? plain : 0);
            }
        }

        /**
         * Two notes hold the same lesson when at least half of all their words of four letters or more are shared
         * (Jaccard similarity 0.5): the same advice reworded, but not a different lesson about the same trouble.
         */
        static boolean sameLesson(String a, String b) {
            if (a.equalsIgnoreCase(b)) return true;
            Set<String> wa = lessonWords(a), wb = lessonWords(b);
            if (wa.isEmpty() || wb.isEmpty()) return false;
            int shared = 0;
            for (String word : wa) if (wb.contains(word)) shared++;
            return 2 * shared >= wa.size() + wb.size() - shared;
        }

        private static Set<String> lessonWords(String text) {
            var words = new java.util.HashSet<String>();
            for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) if (word.length() >= 4) words.add(word);
            return words;
        }

        /** Stop keeping {@code habit}: its notes stay, as plain notes. */
        boolean retire(PlanPreferences.Habit habit) {
            boolean changed = false;
            for (int i = 0; i < notes.size(); i++) {
                if (habit != PlanPreferences.Habit.NONE && habitOf(notes.get(i)) == habit) {
                    notes.set(i, new Note(notes.get(i).tick, notes.get(i).text, null));
                    changed = true;
                }
            }
            return changed;
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

    /** A note, and the habit it teaches by name (absent in files from before habits). */
    record Note(long tick, String text, String habit) { }

    static PlanPreferences.Habit habitOf(Note note) {
        if (note.habit() == null) return PlanPreferences.Habit.NONE;
        try {
            return PlanPreferences.Habit.valueOf(note.habit().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) { // Edited by hand, or from a later version: no habit.
            return PlanPreferences.Habit.NONE;
        }
    }
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
