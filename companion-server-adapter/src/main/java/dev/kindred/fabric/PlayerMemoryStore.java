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
        /** The horse it tamed and keeps (M-01a), by its id, or none. A file from before has none. */
        String horse;
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

    /** Kindred's log, as {@link KindredMod#LOGGER} is, by name: so the stores' file can be read and written where no mod has started (its tests). */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Kindred");

    private Path storesFile(UUID id) { return folder.resolve(id + ".stores.json"); }

    /**
     * Someone it saw open a store of its own (E-43), as it holds it in memory, kept across a restart (F-30d2): the store
     * where it saw them, their name and id as seen, whether they were on its side then, and how many server ticks had
     * passed since it saw them when it was written. A restart's down time does not count, as the server's ticks do not.
     */
    record StoreSighting(int x, int y, int z, String name, boolean teammate, String who, long age) { }

    /**
     * A container it keeps things in, as it knows it (E-43), kept across a restart (F-30d2a): its world and place, whose
     * ({@code OWN} or {@code TEAM}), what it last counted there, and how many server ticks had passed since it last knew
     * them when it was written.
     */
    record StoreCount(String world, int x, int y, int z, String whose, java.util.Map<String, Integer> counts, long age) { }

    /**
     * What it knows of its stores, in one world: those it saw at them and has not yet gone to look (F-30d2), and what it
     * counted in each (F-30d2a). There is none of either in no file, or in a part a file does not have.
     */
    static final class StoreMemory {
        String player = "";
        List<StoreSighting> seen = new ArrayList<>();
        List<StoreCount> stores = new ArrayList<>();
    }

    /**
     * What it knows of its stores, or none (no file: it knows nothing of them); read off the game thread. A file that
     * cannot be read is set aside as {@code .unreadable} and nothing of it is held, rather than a guess: what is not known
     * is not counted or suspected. An entry that does not make sense is left out alone.
     */
    CompletableFuture<StoreMemory> loadStores(UUID id, String name) {
        return CompletableFuture.supplyAsync(() -> {
            Path file = storesFile(id);
            if (!Files.isRegularFile(file)) return null;
            try {
                var read = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), StoreMemory.class);
                if (read == null) throw new IllegalStateException("empty");
                var kept = new StoreMemory();
                kept.player = name;
                for (var each : read.seen == null ? List.<StoreSighting>of() : read.seen) {
                    if (each == null || each.name() == null || each.who() == null || each.age() < 0) continue;
                    try {
                        UUID.fromString(each.who());
                    } catch (IllegalArgumentException notAnId) {
                        continue;
                    }
                    kept.seen.add(each);
                }
                for (var each : read.stores == null ? List.<StoreCount>of() : read.stores) {
                    if (each == null || each.world() == null || each.counts() == null || each.age() < 0
                            || !("OWN".equals(each.whose()) || "TEAM".equals(each.whose()))) continue;
                    if (each.counts().entrySet().stream().anyMatch(c -> c.getKey() == null || c.getValue() == null || c.getValue() <= 0)) continue;
                    kept.stores.add(each);
                }
                return kept;
            } catch (IOException | RuntimeException unreadable) {
                LOG.warn("What {} knows of its stores could not be read; it is set aside and none is held: {}", name, unreadable.toString());
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException kept) {
                    LOG.warn("What {} knows of its stores could not be set aside: {}", name, kept.toString());
                }
                return null;
            }
        }, io);
    }

    /**
     * Write what it knows of its stores, before its file has been read back (F-30d3): on the store's thread, after that
     * read, the file as it stands kept for every store and sighting this copy does not hold, this copy's kept over it.
     * So what it learned before the read is written, and nothing the file held is lost.
     */
    void saveStoresMerging(UUID id, StoreMemory memory) {
        io.execute(() -> {
            var merged = new StoreMemory();
            merged.player = memory.player;
            merged.seen.addAll(memory.seen);
            merged.stores.addAll(memory.stores);
            var file = readQuietly(storesFile(id), StoreMemory.class);
            if (file != null) {
                var places = new java.util.HashSet<String>();
                for (var each : memory.seen) places.add(each.x() + "," + each.y() + "," + each.z());
                for (var each : file.seen == null ? List.<StoreSighting>of() : file.seen) {
                    if (each != null && places.add(each.x() + "," + each.y() + "," + each.z())) merged.seen.add(each);
                }
                var stores = new java.util.HashSet<String>();
                for (var each : memory.stores) stores.add(each.world() + "," + each.x() + "," + each.y() + "," + each.z());
                for (var each : file.stores == null ? List.<StoreCount>of() : file.stores) {
                    if (each != null && stores.add(each.world() + "," + each.x() + "," + each.y() + "," + each.z())) merged.stores.add(each);
                }
            }
            String json;
            try {
                json = gson.toJson(merged);
            } catch (RuntimeException failed) {
                LOG.warn("What {} knows of its stores could not be written: {}", memory.player, failed.toString());
                return;
            }
            writeNow(storesFile(id), json, memory.player);
        });
    }

    /** A file as it stands, or none: one that cannot be read is left for its own read to set aside. On the store's thread only. */
    private <T> T readQuietly(Path file, Class<T> type) {
        try {
            return Files.isRegularFile(file) ? gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), type) : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Write what it knows of its stores, a copy taken on the game thread: turned into its file off it, after any write before it. */
    void saveStores(UUID id, StoreMemory memory) {
        io.execute(() -> {
            String json;
            try {
                json = gson.toJson(memory);
            } catch (RuntimeException failed) {
                LOG.warn("What {} knows of its stores could not be written: {}", memory.player, failed.toString());
                return;
            }
            writeNow(storesFile(id), json, memory.player);
        });
    }

    private Path villagersFile(UUID id) { return folder.resolve(id + ".villagers.json"); }

    /** One side of an offer as its trading screen showed it (F-30d3): an item and how many. */
    record GoodsSeen(String item, int count) { }

    /** An enchantment an offer's result carries, as its screen showed it (F-30d3). */
    record EnchantSeen(String id, int level) { }

    /** An offer as its trading screen showed it (E-45), kept across a restart (F-30d3): asks, gives, carries, uses of how many. */
    record OfferSeen(GoodsSeen first, GoodsSeen second, GoodsSeen result, List<EnchantSeen> enchants, int uses, int maxUses) { }

    /**
     * A villager it has looked at (E-45), as memory holds it, kept across a restart (F-30d3): which, its profession and
     * level, its offers as seen, and how many server ticks had passed since it looked when it was written.
     */
    record VillagerSeen(String villager, String profession, int level, List<OfferSeen> offers, long age) { }

    /** A villager it lets be a while (F-30d3): which, and how many server ticks of the wait were left when it was written. */
    record VillagerWait(String villager, long left) { }

    /**
     * What it knows of the villagers in one world (F-30d3): those it has looked at, those it lets be, and those it has told
     * of once. There is none of any in no file, or in a part a file does not have.
     */
    static final class VillagerMemory {
        String player = "";
        List<VillagerSeen> seen = new ArrayList<>();
        List<VillagerWait> waits = new ArrayList<>();
        List<String> told = new ArrayList<>();
    }

    /**
     * What it knows of the villagers, or none (no file: it knows none); read off the game thread. A file that cannot be
     * read is set aside as {@code .unreadable} and nothing of it is held, rather than a guess. An entry that does not make
     * sense (an id that is not one, a negative age or wait) is left out alone; whether its offers make sense is for the
     * reader, which leaves out a villager whose offers do not.
     */
    CompletableFuture<VillagerMemory> loadVillagers(UUID id, String name) {
        return CompletableFuture.supplyAsync(() -> {
            Path file = villagersFile(id);
            if (!Files.isRegularFile(file)) return null;
            try {
                var read = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), VillagerMemory.class);
                if (read == null) throw new IllegalStateException("empty");
                var kept = new VillagerMemory();
                kept.player = name;
                for (var each : read.seen == null ? List.<VillagerSeen>of() : read.seen) {
                    if (each == null || !isId(each.villager()) || each.profession() == null || each.offers() == null || each.age() < 0) continue;
                    kept.seen.add(each);
                }
                for (var each : read.waits == null ? List.<VillagerWait>of() : read.waits) {
                    if (each != null && isId(each.villager()) && each.left() > 0) kept.waits.add(each);
                }
                for (var each : read.told == null ? List.<String>of() : read.told) if (isId(each)) kept.told.add(each);
                return kept;
            } catch (IOException | RuntimeException unreadable) {
                LOG.warn("What {} knows of the villagers could not be read; it is set aside and none is held: {}", name, unreadable.toString());
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".unreadable"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException kept) {
                    LOG.warn("What {} knows of the villagers could not be set aside: {}", name, kept.toString());
                }
                return null;
            }
        }, io);
    }

    /**
     * Write what it knows of the villagers, before its file has been read back (F-30d3): on the store's thread, after that
     * read, the file as it stands kept for every villager this copy does not hold, this copy's kept over it, those told of
     * of both. So what it learned before the read is written, and nothing the file held is lost.
     */
    void saveVillagersMerging(UUID id, VillagerMemory memory) {
        io.execute(() -> {
            var merged = new VillagerMemory();
            merged.player = memory.player;
            merged.seen.addAll(memory.seen);
            merged.waits.addAll(memory.waits);
            merged.told.addAll(memory.told);
            var file = readQuietly(villagersFile(id), VillagerMemory.class);
            if (file != null) {
                var seen = new java.util.HashSet<String>();
                for (var each : memory.seen) seen.add(each.villager());
                for (var each : file.seen == null ? List.<VillagerSeen>of() : file.seen) if (each != null && seen.add(each.villager())) merged.seen.add(each);
                var waits = new java.util.HashSet<String>();
                for (var each : memory.waits) waits.add(each.villager());
                for (var each : file.waits == null ? List.<VillagerWait>of() : file.waits) if (each != null && waits.add(each.villager())) merged.waits.add(each);
                var told = new java.util.HashSet<>(memory.told);
                for (var each : file.told == null ? List.<String>of() : file.told) if (each != null && told.add(each)) merged.told.add(each);
            }
            String json;
            try {
                json = gson.toJson(merged);
            } catch (RuntimeException failed) {
                LOG.warn("What {} knows of the villagers could not be written: {}", memory.player, failed.toString());
                return;
            }
            writeNow(villagersFile(id), json, memory.player);
        });
    }

    /** Write what it knows of the villagers, a copy taken on the game thread: turned into its file off it, after any write before it. */
    void saveVillagers(UUID id, VillagerMemory memory) {
        io.execute(() -> {
            String json;
            try {
                json = gson.toJson(memory);
            } catch (RuntimeException failed) {
                LOG.warn("What {} knows of the villagers could not be written: {}", memory.player, failed.toString());
                return;
            }
            writeNow(villagersFile(id), json, memory.player);
        });
    }

    private static boolean isId(String text) {
        if (text == null) return false;
        try {
            UUID.fromString(text);
            return true;
        } catch (IllegalArgumentException notAnId) {
            return false;
        }
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
        closeAsync();
    }

    /**
     * Stops the background saving, and is done once every write already asked for is (F-30d3): the store's one thread
     * takes them in order, so a last task after them completes it. Its thread is a daemon, so a stop that did not wait
     * for this could end the process with the last writes (an atlas, the stores, the villagers, at the stop) not made,
     * and a runtime started again at once could read a file before its last write.
     */
    CompletableFuture<Void> closeAsync() {
        var done = new CompletableFuture<Void>();
        try {
            io.execute(() -> done.complete(null));
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            done.complete(null);
        }
        io.shutdown();
        return done;
    }
}
