package dev.kindred.fabric;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * In plain terms: a build battle's arena put back as it was (M-06k3a). Before its plots are marked out, every block in
 * the arena's box is noted, and written to a file; once the podium has been shown, at a moment announced to everyone,
 * the arena is put back block for block, dropping nothing, so the builds' free blocks never leave Builder. A stop puts
 * it back at once; after a crash, the next start does. Until it is put back, no block in it may be broken, and a builder
 * in creative may not throw anything out.
 *
 * Technical: one file per battle, {@code <metadata>/arena-snapshots/<match>.nbt}, compressed NBT: the world, the box,
 * a palette of block states and an index per position (x fastest, then z, then y), and each block entity's own NBT.
 * Written on a daemon thread of its own (a temporary file, forced to disk, moved over whole). Restored on the server
 * thread with {@code SKIP_DROPS}; restoring twice comes to the same. A restored battle's file is deleted after the next
 * whole save of the server ({@link #worldSaved}, from {@code MinecraftServer.saveAll}), so the restored blocks are on
 * disk before the snapshot is forgotten. The boxes closed to breaking, and the builders who may not throw, are kept in
 * static sets read by the mixins, one entry per battle of any runtime.
 */
public final class ArenaSnapshots {
    /** Every live instance, for the save hook. */
    private static final Set<ArenaSnapshots> LIVE = ConcurrentHashMap.newKeySet();
    /** Arena boxes no block may be broken in now, by battle. Server thread. */
    private static final Map<UUID, Closed> CLOSED = new ConcurrentHashMap<>();
    /** Builders in creative for a battle now: none may throw an item out. */
    private static final Set<UUID> BUILDING = ConcurrentHashMap.newKeySet();

    record Closed(RegistryKey<World> world, BlockBox box) { }

    private final Path folder;
    /** Restored battles whose files go at the next whole save. */
    private final Set<UUID> restored = ConcurrentHashMap.newKeySet();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "kindred-arena-snapshots");
        thread.setDaemon(true);
        return thread;
    });

    ArenaSnapshots(Path metadataDirectory) {
        this.folder = metadataDirectory.resolve("arena-snapshots");
        LIVE.add(this);
    }

    Path file(UUID match) { return folder.resolve(match + ".nbt"); }

    /** Every block state and block entity in {@code box} of {@code world}, on the server thread. */
    static NbtCompound capture(ServerWorld world, BlockBox box) {
        var palette = new ArrayList<BlockState>();
        var paletteIndex = new java.util.HashMap<BlockState, Integer>();
        int sizeX = box.getBlockCountX(), sizeY = box.getBlockCountY(), sizeZ = box.getBlockCountZ();
        int[] states = new int[sizeX * sizeY * sizeZ];
        var entities = new NbtList();
        int i = 0;
        var at = new BlockPos.Mutable();
        for (int y = 0; y < sizeY; y++) for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
            at.set(box.getMinX() + x, box.getMinY() + y, box.getMinZ() + z);
            BlockState state = world.getBlockState(at);
            Integer index = paletteIndex.get(state);
            if (index == null) {
                index = palette.size();
                palette.add(state);
                paletteIndex.put(state, index);
            }
            states[i++] = index;
            BlockEntity entity = world.getBlockEntity(at);
            if (entity != null) {
                var tag = entity.createNbtWithIdentifyingData(world.getRegistryManager());
                tag.putInt("kx", at.getX());
                tag.putInt("ky", at.getY());
                tag.putInt("kz", at.getZ());
                entities.add(tag);
            }
        }
        var out = new NbtCompound();
        out.putString("World", world.getRegistryKey().getValue().toString());
        out.putIntArray("Box", new int[] {box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()});
        var paletteTag = new NbtList();
        for (BlockState state : palette) paletteTag.add(NbtHelper.fromBlockState(state));
        out.put("Palette", paletteTag);
        out.putIntArray("States", states);
        out.put("BlockEntities", entities);
        return out;
    }

    static BlockBox box(NbtCompound snapshot) {
        int[] b = snapshot.getIntArray("Box");
        return new BlockBox(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    static RegistryKey<World> world(NbtCompound snapshot) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(snapshot.getString("World")));
    }

    /** Whether every chunk of the snapshot's box is loaded in {@code world}. */
    static boolean loaded(ServerWorld world, NbtCompound snapshot) {
        var box = box(snapshot);
        for (int cx = box.getMinX() >> 4; cx <= box.getMaxX() >> 4; cx++) for (int cz = box.getMinZ() >> 4; cz <= box.getMaxZ() >> 4; cz++) {
            if (!world.isChunkLoaded(cx, cz)) return false;
        }
        return true;
    }

    /**
     * The box put back as the snapshot holds it, on the server thread: every container emptied and every item lying in
     * it cleared first, then each block set with no drops, then each block entity's own data. The blocks changed.
     */
    static int restore(ServerWorld world, NbtCompound snapshot) {
        var box = box(snapshot);
        var blockLookup = world.getRegistryManager().getWrapperOrThrow(RegistryKeys.BLOCK);
        var palette = new ArrayList<BlockState>();
        for (NbtElement tag : snapshot.getList("Palette", NbtElement.COMPOUND_TYPE)) palette.add(NbtHelper.toBlockState(blockLookup, (NbtCompound) tag));
        int[] states = snapshot.getIntArray("States");
        int sizeX = box.getBlockCountX(), sizeY = box.getBlockCountY(), sizeZ = box.getBlockCountZ();
        clearItems(world, box);
        int changed = 0, i = 0;
        var at = new BlockPos.Mutable();
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;
        for (int y = 0; y < sizeY; y++) for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
            at.set(box.getMinX() + x, box.getMinY() + y, box.getMinZ() + z);
            BlockState want = palette.get(states[i++]);
            if (world.getBlockEntity(at) instanceof net.minecraft.inventory.Inventory inventory) inventory.clear();
            if (!world.getBlockState(at).equals(want)) {
                world.setBlockState(at, want, flags);
                changed++;
            }
        }
        for (NbtElement element : snapshot.getList("BlockEntities", NbtElement.COMPOUND_TYPE)) {
            var tag = (NbtCompound) element;
            var pos = new BlockPos(tag.getInt("kx"), tag.getInt("ky"), tag.getInt("kz"));
            var entity = world.getBlockEntity(pos);
            if (entity != null) {
                entity.read(tag, world.getRegistryManager());
                entity.markDirty();
            }
        }
        clearItems(world, box);
        return changed;
    }

    /** Every item entity lying in {@code box} discarded. */
    static int clearItems(ServerWorld world, BlockBox box) {
        var area = new net.minecraft.util.math.Box(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX() + 1, box.getMaxY() + 1, box.getMaxZ() + 1);
        int n = 0;
        for (var item : world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, area, e -> true)) {
            item.discard();
            n++;
        }
        return n;
    }

    CompletableFuture<Void> write(UUID match, NbtCompound snapshot) {
        var copy = snapshot.copy();
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(folder);
                var bytes = new ByteArrayOutputStream();
                NbtIo.writeCompressed(copy, bytes);
                Path temporary = folder.resolve(match + ".nbt.tmp");
                try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(bytes.toByteArray());
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Files.move(temporary, file(match), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io);
    }

    /** Every snapshot left on disk (a battle a crash cut short), by battle. Off the server thread. */
    CompletableFuture<Map<UUID, NbtCompound>> readAll() {
        return CompletableFuture.supplyAsync(() -> {
            var out = new java.util.LinkedHashMap<UUID, NbtCompound>();
            if (!Files.isDirectory(folder)) return out;
            try (var files = Files.list(folder)) {
                for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".nbt")).sorted().toList()) {
                    String name = path.getFileName().toString();
                    try {
                        out.put(UUID.fromString(name.substring(0, name.length() - 4)), NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes()));
                    } catch (IllegalArgumentException | IOException unreadable) {
                        KindredMod.LOGGER.error("An arena snapshot could not be read and is left for an operator: {}", path, unreadable);
                    }
                }
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
            return out;
        }, io);
    }

    /** A battle's arena put back: its file goes at the next whole save. */
    void markRestored(UUID match) { restored.add(match); }

    /** Whether a restored battle's file is still waiting for a whole save. */
    boolean awaitingSave(UUID match) { return restored.contains(match); }

    /** The server saved everything: the files of arenas already put back are forgotten. From the save hook. */
    public static void worldSaved() {
        for (ArenaSnapshots each : LIVE) each.forgetRestored();
    }

    private void forgetRestored() {
        for (UUID match : List.copyOf(restored)) {
            restored.remove(match);
            // On the saving thread, at once: a file or two, once a battle; the writer's thread may be shut by now.
            try {
                Files.deleteIfExists(file(match));
            } catch (IOException failure) {
                KindredMod.LOGGER.error("A restored arena's snapshot could not be deleted; it is restored again, unchanged, at the next start", failure);
            }
        }
        if (stopped && restored.isEmpty()) LIVE.remove(this);
    }

    private volatile boolean stopped;

    // The mixins' view: boxes closed to breaking, and builders who may not throw.

    static void close(UUID match, RegistryKey<World> world, BlockBox box) { CLOSED.put(match, new Closed(world, box)); }

    static void open(UUID match) { CLOSED.remove(match); }

    /** Whether no block at {@code pos} of {@code world} may be broken now: inside an arena not yet put back. */
    public static boolean closed(World world, BlockPos pos) {
        if (CLOSED.isEmpty()) return false;
        for (Closed c : CLOSED.values()) if (c.world() == world.getRegistryKey() && c.box().contains(pos)) return true;
        return false;
    }

    static void building(UUID player, boolean now) {
        if (now) BUILDING.add(player); else BUILDING.remove(player);
    }

    /** Whether {@code player} is building in creative for a battle now, and may not throw anything out. */
    public static boolean building(UUID player) { return !BUILDING.isEmpty() && BUILDING.contains(player); }

    /** At the runtime's stop: the writer shut; still told of the next whole save while a restored arena waits for it. */
    void close() {
        stopped = true;
        io.shutdown();
        if (restored.isEmpty()) LIVE.remove(this);
    }
}
