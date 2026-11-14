package dev.kindred.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.kindred.core.customs.ServerCustoms;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * In plain terms: the server's customs, as people who opted in show them (E-47): the depths they mine their ores at.
 * It hears only of ores people break. It reads no chat, no inventory, and nothing of anyone who has not opted in, nor
 * of the AI players themselves. It keeps what it learns in the world's folder, and deletes a person's share there as
 * soon as they opt out.
 *
 * Technical: E-47 under D10, over core's {@link ServerCustoms}. Used on the server thread only; the file
 * ({@code <world>/kindred/customs.json}) is read when the server starts, before its first tick, and written off the
 * thread, a copy taken on it, at most once a minute while it learns and at once on an opt-in or an opt-out; a write
 * replaces the file whole.
 */
final class ServerCustomsKeeper implements AutoCloseable {
    /** How often what it learned is written, at most, in ticks: a minute. */
    static final int SAVE_EVERY = 1_200;

    private final Path file;
    private final ServerCustoms customs;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kindred-server-customs");
        thread.setDaemon(true);
        return thread;
    });
    private boolean dirty;
    /** When it last wrote what it learned: never, at first, so the first thing learned is written at the next tick. */
    private long savedAt = -SAVE_EVERY;

    private ServerCustomsKeeper(Path file, ServerCustoms customs) {
        this.file = file;
        this.customs = customs;
    }

    /** The customs kept in the world's mod folder, read now (the server has not ticked yet); afresh if none or unreadable. */
    static ServerCustomsKeeper open(Path worldKindredFolder) {
        Path file = worldKindredFolder.resolve("customs.json");
        ServerCustoms customs = new ServerCustoms();
        try {
            if (Files.isRegularFile(file)) {
                customs = ServerCustoms.of(DurableFiles.parse(file, new Gson(), ServerCustoms.Snapshot.class));
            }
        } catch (IOException | RuntimeException unreadable) {
            KindredMod.LOGGER.warn("The server's customs could not be read; it starts with none of it, and the file is kept: {}", unreadable.toString());
        }
        return new ServerCustomsKeeper(file, customs);
    }

    /** The customs themselves, for the players' plans to lean on. */
    ServerCustoms customs() {
        return customs;
    }

    /** Whether {@code player} has opted in. */
    boolean consents(ServerPlayerEntity player) {
        return customs.consents(player.getUuid());
    }

    /** {@code player} opts in, for themselves. True if they had not already; the file written now. */
    boolean optIn(ServerPlayerEntity player) {
        if (player instanceof PreparedNativePlayer) return false;
        boolean changed = customs.optIn(player.getUuid());
        if (changed) save();
        KindredMod.LOGGER.info("{} opted in to teaching the players this server's customs", player.getName().getString());
        return changed;
    }

    /** {@code player} opts out: all learned from them deleted, here and in the file, now. True if there was any. */
    boolean optOut(ServerPlayerEntity player) {
        boolean changed = customs.optOut(player.getUuid());
        save();
        KindredMod.LOGGER.info("{} opted out of teaching the players this server's customs; what was learned from them is deleted",
                player.getName().getString());
        return changed;
    }

    /** A block broken by a player: an ore, in the overworld, by a person who consents, is learned from; nothing else is. */
    void broke(World world, ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (player instanceof PreparedNativePlayer || world.getRegistryKey() != World.OVERWORLD) return;
        ServerCustoms.Ore ore = ore(state);
        if (ore == null || !customs.consents(player.getUuid())) return;
        customs.mined(player.getUuid(), ore, pos.getY());
        dirty = true;
    }

    private static ServerCustoms.Ore ore(BlockState state) {
        if (state.isOf(Blocks.IRON_ORE) || state.isOf(Blocks.DEEPSLATE_IRON_ORE)) return ServerCustoms.Ore.IRON;
        if (state.isOf(Blocks.DIAMOND_ORE) || state.isOf(Blocks.DEEPSLATE_DIAMOND_ORE)) return ServerCustoms.Ore.DIAMOND;
        if (state.isOf(Blocks.COAL_ORE) || state.isOf(Blocks.DEEPSLATE_COAL_ORE)) return ServerCustoms.Ore.COAL;
        return null;
    }

    /** Once a tick: what was learned written, at most once a minute. */
    void tick(long tick) {
        if (dirty && tick - savedAt >= SAVE_EVERY) {
            savedAt = tick;
            save();
        }
    }

    private void save() {
        dirty = false;
        var snapshot = customs.snapshot();
        io.execute(() -> {
            try {
                DurableFiles.write(file, gson.toJson(snapshot)); // F-31h: forced to disk, folder and all.
            } catch (IOException | RuntimeException failed) {
                KindredMod.LOGGER.warn("The server's customs could not be written: {}", failed.toString());
            }
        });
    }

    /** The last write, then the writer's thread ended. */
    @Override
    public void close() {
        if (dirty) save();
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
