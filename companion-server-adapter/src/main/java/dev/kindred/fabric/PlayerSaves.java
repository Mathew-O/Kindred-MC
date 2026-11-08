package dev.kindred.fabric;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

/**
 * In plain terms: writes a player's save file in the background after a trade, so the server never waits on the disk,
 * and makes sure nothing reads or overwrites that file with an older copy until the write is done.
 *
 * Technical: The market's two-phase player save (M-06g/M-06h). Phase one, on the server thread: the player's state is
 * written to a fresh NBT tree ({@link ServerPlayerEntity#writeNbt}), which shares nothing with the live entity. Phase
 * two, on one ordered writer thread: the tree goes to a temporary file in the player-data directory, the current file
 * is copied to {@code <uuid>.dat_old} as vanilla keeps it, and the temporary file is moved over {@code <uuid>.dat}
 * atomically, so the file is always the old state or the new one. While a write is pending for a player, every other
 * save of that player is routed through the same writer, so a later state is never overwritten by an earlier one
 * (vanilla's {@code PlayerSaveHandler.savePlayerData}, by mixin, and a native body's own save), and every read waits
 * for it (a native body's background read chains after it; vanilla's own load, at a join within those milliseconds,
 * waits on the server thread, the one place a tick can wait on this disk write). The server's stop drains the writer.
 */
public final class PlayerSaves {
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "kindred-player-saves");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<UUID, CompletableFuture<Void>> PENDING = new ConcurrentHashMap<>();

    private PlayerSaves() { }

    /** A write of this player's file is waiting or under way. */
    public static boolean pending(UUID player) { return PENDING.containsKey(player); }

    /**
     * Phase one now, on the server thread; the future completes on the writer thread when the file is in place, or
     * exceptionally when it could not be written (the file is then still its earlier state).
     */
    static CompletableFuture<Void> save(ServerPlayerEntity player) {
        if (!player.server.isOnThread()) throw new IllegalStateException("A player is snapshotted on the server thread");
        return enqueue(player.getUuid(), player.server.getSavePath(WorldSavePath.PLAYERDATA), player.writeNbt(new NbtCompound()));
    }

    /** A snapshot already taken, written after every earlier one. */
    public static CompletableFuture<Void> enqueue(UUID player, Path directory, NbtCompound snapshot) {
        var done = CompletableFuture.runAsync(() -> write(directory, player, snapshot), WRITER);
        PENDING.put(player, done);
        done.whenComplete((ignored, failure) -> PENDING.remove(player, done));
        return done;
    }

    /** Completes, never exceptionally, once every write of this player's file queued so far is finished. */
    static CompletableFuture<Void> written(UUID player) {
        var pending = PENDING.get(player);
        return pending == null ? CompletableFuture.completedFuture(null) : pending.handle((ignored, failure) -> null);
    }

    /** Waits for this player's pending write, for a read that cannot be chained. */
    public static void await(UUID player) {
        try {
            written(player).get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("A player's save is still being written; reconcile rather than reading an older copy", failure);
        }
    }

    /** At the server's stop, after vanilla has saved every player: every queued write finished. */
    static void drain() {
        try {
            CompletableFuture.runAsync(() -> { }, WRITER).get(60, TimeUnit.SECONDS);
        } catch (Exception failure) {
            KindredMod.LOGGER.error("Player saves were still being written at the stop", failure);
        }
    }

    private static void write(Path directory, UUID player, NbtCompound snapshot) {
        Path file = directory.resolve(player + ".dat");
        Path temp = directory.resolve(player + ".dat_" + UUID.randomUUID());
        try {
            Files.createDirectories(directory);
            try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                NbtIo.writeCompressed(snapshot, output);
            }
            if (Files.exists(file)) Files.copy(file, directory.resolve(player + ".dat_old"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            throw new java.io.UncheckedIOException("Player save could not be written", failure);
        }
    }
}
