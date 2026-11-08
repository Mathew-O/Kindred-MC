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
 * In plain terms: what each AI player remembers about each world from playing in it, kept across restarts:
 * the places that matter on the way to the End, the bases and trading hall it built, and any work an
 * administrator set it to. Only what it saw for itself is kept, and it is saved to small files without
 * holding up the game.
 *
 * Technical: What each AI player has learnt about each world by playing in it, kept across restarts: the
 * places that matter on the way to the End (the portals it went through, the fortress it found, where its
 * eyes' flights cross, the End portal) and each eye flight it watched. One small JSON file per player in the
 * world's {@code kindred/memory} folder. Only what the player saw itself is kept, as it saw it; a restart no
 * longer sends it to throw eyes again, or to find a fortress again that it already found. Beside it, in files of their
 * own: its bases, its work, its atlas, what it knows of the others ({@code <uuid>.peers.json}, E-50) and how it stands with
 * them ({@code <uuid>.rapport.json}, E-24a). Reads and writes
 * run on their own thread; a write replaces the file whole.
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

    /**
     * A base the player built or is building (M-01c): its stage ({@code cabin}, {@code house}, {@code manor}), where
     * its plan's origin is, whether it stands whole, and the style it was grown in (E-29a): the seed block, its wood,
     * its plan's variant and its quarter turns. A base recorded before E-29a has no wood: it is the first plan, in oak,
     * its origin its floor's north-west corner.
     */
    record Base(String stage, String dimension, int x, int y, int z, boolean complete, String seed, String wood, int variant, int turns) {
        /** A base recorded before building styles existed: the first plan, in oak. */
        Base(String stage, String dimension, int x, int y, int z, boolean complete) {
            this(stage, dimension, x, y, z, complete, null, null, 0, 0);
        }

        /** The same base, now standing whole. */
        Base finished() {
            return new Base(stage, dimension, x, y, z, true, seed, wood, variant, turns);
        }
    }

    /**
     * The player's trading hall by its base (E-45d): where its origin is (the north-west corner of its floor, never
     * turned), how many cells it has, its wood, and whether it stands whole. A file from before E-45d has none.
     */
    record Hall(String dimension, int x, int y, int z, int cells, String wood, boolean complete) {
        /** The same hall, now standing whole. */
        Hall finished() {
            return new Hall(dimension, x, y, z, cells, wood, true);
        }
    }

    /**
     * The ranch the player chose for itself by its base (M-01a): its pen's north-west post, the kind it keeps there, and
     * whether the ranch has run its course. A file from before has none.
     */
    record Pen(String dimension, int x, int y, int z, String kind, boolean complete, long ranAt) {
        /** A pen with no run recorded yet (a file from before reads its {@code ranAt} as 0). */
        Pen(String dimension, int x, int y, int z, String kind, boolean complete) {
            this(dimension, x, y, z, kind, complete, 0);
        }

        /** The same pen, its ranch run at world time {@code at}. */
        Pen finished(long at) {
            return new Pen(dimension, x, y, z, kind, true, at);
        }
    }

    /** The wheat plot by its base (M-01a): its north-west soil cell, two cells a side. A file from before has none. */
    record Plot(String dimension, int x, int y, int z) { }

    /** The player's bases in one world, oldest first, and the place the first was sited by. Game thread only. */
    static final class Bases {
        String player = "";
        Place anchor;
        /** The seed block it was given to grow its builds from (E-29a), or none. */
        String seed;
        List<Base> built = new ArrayList<>();
        /** Its trading hall (E-45d), or none. */
        Hall hall;
        /** Its own ranch (M-01a), or none. */
        Pen ranch;
        /** Its wheat plot (M-01a), or none. */
        Plot plot;
        /** Its pens after the first (M-01a), a pen per kind, oldest first; the first stays {@link #ranch}. A file from before has none. */
        List<Pen> pens = new ArrayList<>();

        /** All its pens, the first first. */
        List<Pen> allPens() {
            var all = new ArrayList<Pen>();
            if (ranch != null) all.add(ranch);
            if (pens != null) all.addAll(pens);
            return all;
        }

        /** The pen {@code pen} recorded as run at world time {@code at}; false if it is not one of its pens. */
        boolean finish(Pen pen, long at) {
            if (pen == null) return false;
            if (pen.equals(ranch)) {
                ranch = ranch.finished(at);
                return true;
            }
            if (pens == null) return false;
            int i = pens.indexOf(pen);
            if (i < 0) return false;
            pens.set(i, pen.finished(at));
            return true;
        }

        /** A new pen recorded: the first as {@link #ranch}, the rest after it. */
        void addPen(Pen pen) {
            if (ranch == null) ranch = pen;
            else {
                if (pens == null) pens = new ArrayList<>();
                pens.add(pen);
            }
        }
    }

    /**
     * The work an operator set the player to, kept so that it is taken up again after a restart (F-13): a build
     * ({@code build}, its plan by the name the command gave it: {@code hut-5x5x3}, {@code walltower-3}, a schematic's
     * name) at its origin, turned so many quarters, with the scaffold blocks it put up and has still to take down; or a
     * farm ward's field ({@code field}) by its keep's spot. What it has built, tilled or sown is read from the world.
     */
    record Work(String kind, String plan, String dimension, int x, int y, int z, int turns, List<int[]> scaffold) { }

    private final Path folder;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kindred-player-memory");
        thread.setDaemon(true);
        return thread;
    });

    /** The memory kept in the given world's mod folder. */
    PlayerMemoryStore(Path worldKindredFolder) {
        this.folder = worldKindredFolder.resolve("memory");
    }

    private Path file(UUID id) { return folder.resolve(id + ".json"); }

    private Path basesFile(UUID id) { return folder.resolve(id + ".bases.json"); }

    private Path workFile(UUID id) { return folder.resolve(id + ".work.json"); }

    private Path atlasFile(UUID id) { return folder.resolve(id + ".atlas.json"); }

    private Path peersFile(UUID id) { return folder.resolve(id + ".peers.json"); }

    /** What this player knows of the others in this world (E-50), or none; read off the game thread. */
    CompletableFuture<dev.kindred.core.social.PeerLedger.Snapshot> loadPeers(UUID id, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.isRegularFile(peersFile(id))) {
                    return gson.fromJson(Files.readString(peersFile(id), StandardCharsets.UTF_8), dev.kindred.core.social.PeerLedger.Snapshot.class);
                }
            } catch (IOException | RuntimeException unreadable) {
                KindredMod.LOGGER.warn("What {} knows of the others could not be read; starting afresh: {}", name, unreadable.toString());
            }
            return null;
        }, io);
    }

    /** Write what it knows of the others, a copy taken on the game thread: turned into its file off it, after any write before it. */
    void savePeers(UUID id, String name, dev.kindred.core.social.PeerLedger.Snapshot snapshot) {
        io.execute(() -> {
            String json;
            try {
                json = gson.toJson(snapshot);
            } catch (RuntimeException failed) {
                KindredMod.LOGGER.warn("What {} knows of the others could not be written: {}", name, failed.toString());
                return;
            }
            writeNow(peersFile(id), json, name);
        });
    }

    private Path rapportFile(UUID id) { return folder.resolve(id + ".rapport.json"); }

    /** How this player stands with each it knows (E-24a), or none; read off the game thread. */
    CompletableFuture<dev.kindred.core.social.Rapport.Snapshot> loadRapport(UUID id, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.isRegularFile(rapportFile(id))) {
                    return gson.fromJson(Files.readString(rapportFile(id), StandardCharsets.UTF_8), dev.kindred.core.social.Rapport.Snapshot.class);
                }
            } catch (IOException | RuntimeException unreadable) {
                KindredMod.LOGGER.warn("How {} stands with the others could not be read; starting afresh: {}", name, unreadable.toString());
            }
            return null;
        }, io);
    }

    /** Write how it stands with the others, a copy taken on the game thread, off it, after any write before it. */
    void saveRapport(UUID id, String name, dev.kindred.core.social.Rapport.Snapshot snapshot) {
        io.execute(() -> {
            String json;
            try {
                json = gson.toJson(snapshot);
            } catch (RuntimeException failed) {
                KindredMod.LOGGER.warn("How {} stands with the others could not be written: {}", name, failed.toString());
                return;
            }
            writeNow(rapportFile(id), json, name);
        });
    }

    /** This player's atlas of this world (F-30), or none; read off the game thread. */
    CompletableFuture<dev.kindred.core.atlas.Atlas.Snapshot> loadAtlas(UUID id, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.isRegularFile(atlasFile(id))) {
                    return gson.fromJson(Files.readString(atlasFile(id), StandardCharsets.UTF_8), dev.kindred.core.atlas.Atlas.Snapshot.class);
                }
            } catch (IOException | RuntimeException unreadable) {
                KindredMod.LOGGER.warn("The atlas of {} could not be read; starting afresh: {}", name, unreadable.toString());
            }
            return null;
        }, io);
    }

    /** Write the atlas's snapshot, a copy taken on the game thread: turned into its file off it, after any write before it. */
    void saveAtlas(UUID id, String name, dev.kindred.core.atlas.Atlas.Snapshot snapshot) {
        io.execute(() -> {
            String json;
            try {
                json = gson.toJson(snapshot);
            } catch (RuntimeException failed) {
                KindredMod.LOGGER.warn("The atlas of {} could not be written: {}", name, failed.toString());
                return;
            }
            writeNow(atlasFile(id), json, name);
        });
    }

    /** The work this player was at in this world, or null; read off the game thread. */
    CompletableFuture<Work> loadWork(UUID id, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.isRegularFile(workFile(id))) return gson.fromJson(Files.readString(workFile(id), StandardCharsets.UTF_8), Work.class);
            } catch (IOException | RuntimeException unreadable) {
                KindredMod.LOGGER.warn("The work of {} could not be read; none taken up: {}", name, unreadable.toString());
            }
            return null;
        }, io);
    }

    /** Write the work taken now on the game thread; the write itself happens off it, after any before it. */
    void saveWork(UUID id, String name, Work work) {
        write(id, workFile(id), gson.toJson(work), name);
    }

    /** The work is over: nothing is taken up after a restart. In order with the writes before it. */
    void clearWork(UUID id, String name) {
        io.execute(() -> {
            try {
                Files.deleteIfExists(workFile(id));
            } catch (IOException failed) {
                KindredMod.LOGGER.warn("The work of {} could not be cleared: {}", name, failed.toString());
            }
        });
    }

    /** This player's bases in this world, or none; read off the game thread. */
    CompletableFuture<Bases> loadBases(UUID id, String name) {
        return CompletableFuture.supplyAsync(() -> {
            Bases bases = null;
            try {
                if (Files.isRegularFile(basesFile(id))) bases = gson.fromJson(Files.readString(basesFile(id), StandardCharsets.UTF_8), Bases.class);
            } catch (IOException | RuntimeException unreadable) {
                KindredMod.LOGGER.warn("The bases of {} could not be read; starting afresh: {}", name, unreadable.toString());
            }
            if (bases == null) bases = new Bases();
            if (bases.built == null) bases.built = new ArrayList<>();
            bases.player = name;
            return bases;
        }, io);
    }

    /** Write a copy of the player's bases taken now on the game thread; the write itself happens off it. */
    void saveBases(UUID id, Bases bases) {
        write(id, basesFile(id), gson.toJson(bases), bases.player);
    }

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
        write(id, file(id), gson.toJson(memory), memory.player);
    }

    private void write(UUID id, Path target, String json, String player) {
        io.execute(() -> writeNow(target, json, player));
    }

    /** On the store's own thread: the file replaced whole, so a crash leaves the last complete one. */
    private void writeNow(Path target, String json, String player) {
        try {
            Files.createDirectories(folder);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException plain) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failed) {
            KindredMod.LOGGER.warn("Memory of {} could not be written: {}", player, failed.toString());
        }
    }

    /** Stops the background saving once the writes already asked for are done. */
    @Override
    public void close() {
        io.shutdown();
    }
}
