package dev.kindred.fabric;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

/**
 * In plain terms: a builder's own things, set aside while it builds in creative (M-06k3). When a build battle starts, what
 * each builder holds, its ender chest and its game mode are written to a file before anything of theirs is touched; when
 * it ends, or the server stops, or the builder comes back after being away for the end or after a crash, everything the
 * match gave or creative made is cleared and exactly what was set aside is put back. Nothing is made: what comes back
 * is what went in, and the file is the only other place it is while the match runs. Free blocks never leave Builder.
 *
 * Technical: one file per player, {@code <metadata>/builder-holds/<uuid>.nbt}, compressed NBT ({@code Inventory} as
 * {@code PlayerInventory.writeNbt} writes it, {@code EnderItems} as the ender chest writes them, {@code GameMode}), written
 * on a daemon thread of its own to a temporary file, forced to disk and moved over whole; never overwritten while one
 * exists (a builder in twice keeps its first). Restoring is wholesale (clear, then read), so doing it twice comes to the
 * same; the file is deleted only after the restored player has been saved.
 */
final class BuilderHolds {
    private final Path folder;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "kindred-builder-holds");
        thread.setDaemon(true);
        return thread;
    });

    BuilderHolds(Path metadataDirectory) {
        this.folder = metadataDirectory.resolve("builder-holds");
    }

    Path file(UUID player) { return folder.resolve(player + ".nbt"); }

    /** What {@code player} holds now, its ender chest and its game mode, on the server thread. */
    static NbtCompound capture(ServerPlayerEntity player) {
        var hold = new NbtCompound();
        hold.put("Inventory", player.getInventory().writeNbt(new NbtList()));
        hold.put("EnderItems", player.getEnderChestInventory().toNbtList(player.getRegistryManager()));
        hold.putInt("GameMode", player.interactionManager.getGameMode().getId());
        return hold;
    }

    /**
     * Everything {@code player} holds, its cursor and its ender chest cleared, then the hold put back, on the server thread;
     * the game mode it had is returned for the caller to set (an AI player's body is told first).
     */
    static GameMode restore(ServerPlayerEntity player, NbtCompound hold) {
        var inventory = player.getInventory();
        inventory.clear();
        player.currentScreenHandler.setCursorStack(net.minecraft.item.ItemStack.EMPTY);
        inventory.readNbt(hold.getList("Inventory", NbtElement.COMPOUND_TYPE));
        var ender = player.getEnderChestInventory();
        ender.clear();
        ender.readNbtList(hold.getList("EnderItems", NbtElement.COMPOUND_TYPE), player.getRegistryManager());
        player.playerScreenHandler.sendContentUpdates();
        return GameMode.byId(hold.getInt("GameMode"));
    }

    /** Writes the hold unless one is already kept; true when this call wrote it. Off the server thread. */
    CompletableFuture<Boolean> write(UUID player, NbtCompound hold) {
        var copy = hold.copy();
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path target = file(player);
                if (Files.exists(target)) return false;
                Files.createDirectories(folder);
                var bytes = new ByteArrayOutputStream();
                NbtIo.writeCompressed(copy, bytes);
                Path temporary = folder.resolve(player + ".nbt.tmp");
                try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(bytes.toByteArray());
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io);
    }

    /** The hold kept for {@code player}, if any. Off the server thread. */
    CompletableFuture<Optional<NbtCompound>> read(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            Path path = file(player);
            if (!Files.exists(path)) return Optional.<NbtCompound>empty();
            try {
                return Optional.of(NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes()));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io);
    }

    /** Forgets the hold once what it held is back and saved. Off the server thread. */
    CompletableFuture<Void> delete(UUID player) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(file(player));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io);
    }

    /** As {@link #delete}, at the stop, waited for. */
    void deleteNow(UUID player) {
        try {
            delete(player).join();
        } catch (RuntimeException failure) {
            KindredMod.LOGGER.error("A builder's hold could not be deleted at the stop; it is restored again at the next login", failure);
        }
    }

    void close() { io.shutdown(); }
}
