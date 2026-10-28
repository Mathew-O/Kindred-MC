package dev.kindred.fabric;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * In plain terms: reads an AI player's save file (its inventory and so on) in the background, before the body
 * joins. It only reads; Minecraft's own saving stays the only thing that writes it.
 *
 * Technical: Read-only native-save preparation. Vanilla PlayerSaveHandler remains the only physical-state
 * writer.
 */
final class NativePlayerSaves {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private NativePlayerSaves() { }

    @FunctionalInterface interface Reader {
        CompletableFuture<Optional<Bytes>> read(Path directory, UUID nativeId);
    }

    record Bytes(byte[] compressed) {
        Bytes { compressed = compressed.clone(); }
        @Override public byte[] compressed() { return compressed.clone(); }

        /** The save's contents, unpacked. */
        NbtCompound decode(MinecraftServer server) {
            if (!server.isOnThread()) throw new IllegalStateException("Apply native data on the server thread");
            try { return NbtIo.readCompressed(new ByteArrayInputStream(compressed), NbtSizeTracker.of(MAX_BYTES)); }
            catch (IOException failure) { throw new IllegalStateException("Native save is unreadable; reconcile rather than replacing it", failure); }
        }
    }

    /** Reads one player's save file in the background, or nothing if there is none yet. */
    static CompletableFuture<Optional<Bytes>> read(Path playerDirectory, UUID nativeId) {
        Path file = playerDirectory.resolve(nativeId + ".dat");
        // Workers receive only a path/UUID and return defensively copied bytes, never Minecraft state.
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.notExists(file)) return Optional.empty();
                try (var input = Files.newInputStream(file)) {
                    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                    if (bytes.length > MAX_BYTES) throw new IOException("Native player save exceeds the size limit");
                    return Optional.of(new Bytes(bytes));
                }
            } catch (IOException failure) { throw new CompletionException(failure); }
        });
    }
}
