package dev.kindred.fabric;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * In plain terms: when a player dies, a copy of the ground round it, 33 by 17 by 33 blocks with the mobs in it, and of
 * its own state, kept so that the death can be played again as a test.
 *
 * Technical: F-27. Off unless {@link KindredMod} turns it on ({@code KINDRED_REPLAYS}, on by default under an evaluation
 * run). The structure and the state are taken on the server thread, which the world requires, and the files are
 * written off it, into {@code <dir>/<player>-<tick>/} as {@code replay.snbt} and {@code replay.json}. One a minute at most
 * for each player. A note of each player's vitals once a second gives the replay the health, food and breath it had ten
 * seconds before it died: at the moment of death its health is none. Nothing here changes the world or the player.
 */
final class DeathReplays implements AutoCloseable {
    static final int WIDTH = 33, HEIGHT = 17;
    /** A capture at most once a minute for each player; its vitals as they were ten seconds before. */
    private static final long GAP = 1200, BEFORE = 200;
    private static final int KEPT_VITALS = 16, LOG_LINES = 20;

    record Vitals(long tick, float health, int food, float saturation, int air, float absorption) { }

    /** What the runtime knows of the player as it dies, for the replay's own record. */
    record Context(String kind, String goal, String stage, String task, String stance, String habits, String cause) { }

    private final Path dir;
    private final Path log;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kindred-replays");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ArrayDeque<Vitals>> vitals = new HashMap<>();
    private final Map<String, Long> last = new HashMap<>();
    private Capture latest;

    DeathReplays(Path dir, Path log) {
        this.dir = dir;
        this.log = log;
    }

    Path dir() { return dir; }

    /** Once a second on the server thread: the player's vitals, the last sixteen seconds of them kept. */
    void note(String player, ServerPlayerEntity body, long tick) {
        var kept = vitals.computeIfAbsent(player, key -> new ArrayDeque<>());
        if (kept.size() >= KEPT_VITALS) kept.removeFirst();
        var hunger = body.getHungerManager();
        kept.addLast(new Vitals(tick, body.getHealth(), hunger.getFoodLevel(), hunger.getSaturationLevel(), body.getAir(), body.getAbsorptionAmount()));
    }

    /**
     * The capture, on the server thread as the player dies and before vanilla drops what it holds. Returns what it
     * took on the server thread, in nanoseconds, and the files' write, or nothing when one was taken in the last minute.
     */
    java.util.Optional<Capture> capture(String player, ServerPlayerEntity body, long tick, Context context) {
        Long before = last.get(player);
        if (before != null && tick - before < GAP) return java.util.Optional.empty();
        last.put(player, tick);
        long start = System.nanoTime();
        var world = body.getServerWorld();
        BlockPos feet = body.getBlockPos();
        BlockPos origin = feet.add(-WIDTH / 2, -HEIGHT / 2, -WIDTH / 2);
        var template = new StructureTemplate();
        // Air is left out: a test clears its area before it places its structure, and an underground scene is still
        // more than half a megabyte of blocks without it.
        template.saveFromWorld(world, origin, new Vec3i(WIDTH, HEIGHT, WIDTH), true, Blocks.AIR);
        JsonObject state = state(player, body, origin, tick, context);
        long spent = System.nanoTime() - start;
        Path folder = dir.resolve(player + "-" + tick);
        Future<?> written = writer.submit(() -> write(folder, template, state, player));
        latest = new Capture(folder, spent, written);
        KindredMod.LOGGER.info("{} death replay captured in {} ms on the server thread", player, String.format(java.util.Locale.ROOT, "%.1f", spent / 1e6));
        return java.util.Optional.of(latest);
    }

    /** The last capture taken, for a fixture to time and read back. */
    java.util.Optional<Capture> latestForTest() { return java.util.Optional.ofNullable(latest); }

    record Capture(Path folder, long nanosOnThread, Future<?> written) { }

    private JsonObject state(String player, ServerPlayerEntity body, BlockPos origin, long tick, Context context) {
        var world = body.getServerWorld();
        var ops = world.getRegistryManager().getOps(JsonOps.INSTANCE);
        JsonObject json = new JsonObject();
        json.addProperty("kind", context.kind());
        json.addProperty("player", player);
        json.addProperty("tick", tick);
        json.addProperty("dimension", world.getRegistryKey().getValue().toString());
        json.add("origin", array(origin.getX(), origin.getY(), origin.getZ()));
        json.add("size", array(WIDTH, HEIGHT, WIDTH));
        JsonArray at = new JsonArray();
        at.add(body.getX() - origin.getX());
        at.add(body.getY() - origin.getY());
        at.add(body.getZ() - origin.getZ());
        json.add("at", at);
        json.addProperty("yaw", body.getYaw());
        json.addProperty("pitch", body.getPitch());
        // Its vitals ten seconds before (the oldest kept if it has not been about that long); at death its health is none.
        Vitals then = null;
        for (Vitals v : vitals.getOrDefault(player, new ArrayDeque<>())) if (v.tick() <= tick - BEFORE || then == null) then = v;
        if (then == null) then = new Vitals(tick, body.getMaxHealth(), 20, 5, body.getMaxAir(), 0);
        json.addProperty("health", then.health());
        json.addProperty("food", then.food());
        json.addProperty("saturation", then.saturation());
        json.addProperty("air", then.air());
        json.addProperty("absorption", then.absorption());
        JsonArray effects = new JsonArray();
        for (var effect : body.getStatusEffects()) {
            JsonObject e = new JsonObject();
            e.addProperty("id", effect.getEffectType().getKey().map(key -> key.getValue().toString()).orElse("unknown"));
            e.addProperty("amplifier", effect.getAmplifier());
            e.addProperty("duration", effect.getDuration());
            effects.add(e);
        }
        json.add("effects", effects);
        // What it held as it died, before vanilla drops it: every slot with its components, armour and the off hand.
        var inventory = body.getInventory();
        JsonArray items = new JsonArray();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            var encoded = ItemStack.CODEC.encodeStart(ops, stack).result();
            if (encoded.isEmpty()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            item.add("stack", encoded.get());
            items.add(item);
        }
        json.add("inventory", items);
        json.addProperty("selected", inventory.selectedSlot);
        json.addProperty("timeOfDay", world.getTimeOfDay() % 24_000);
        json.addProperty("raining", world.isRaining());
        json.addProperty("thundering", world.isThundering());
        json.addProperty("difficulty", world.getDifficulty().getName());
        json.addProperty("goal", context.goal());
        json.addProperty("stage", context.stage());
        json.addProperty("task", context.task());
        json.addProperty("stance", context.stance());
        json.addProperty("habits", context.habits());
        json.addProperty("cause", context.cause());
        json.addProperty("ticks", 1200);
        return json;
    }

    private void write(Path folder, StructureTemplate template, JsonObject state, String player) {
        try {
            Files.createDirectories(folder);
            state.add("log", lines(player));
            String snbt = NbtHelper.toNbtProviderString(template.writeNbt(new NbtCompound()));
            Files.writeString(folder.resolve("replay.snbt"), snbt, StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("replay.json"), new GsonBuilder().setPrettyPrinting().create().toJson(state), StandardCharsets.UTF_8);
            KindredMod.LOGGER.info("{} death replay written to {} ({} KB)", player, folder, snbt.length() / 1024);
        } catch (IOException | RuntimeException failure) {
            KindredMod.LOGGER.warn("{} death replay not written to {}: {}", player, folder, failure.toString());
        }
    }

    /** Its own last twenty decisions as the log has them: its goal's steps, its tasks' ends and its deaths. */
    private JsonArray lines(String player) {
        JsonArray out = new JsonArray();
        if (log == null || !Files.isRegularFile(log)) return out;
        try (var file = new java.io.RandomAccessFile(log.toFile(), "r")) {
            long length = file.length(), from = Math.max(0, length - 512 * 1024);
            byte[] tail = new byte[(int) (length - from)];
            file.seek(from);
            file.readFully(tail);
            List<String> mine = new ArrayList<>();
            for (String line : new String(tail, StandardCharsets.UTF_8).split("\n")) {
                if (line.contains("] " + player + " goal at ") || line.contains(" " + player + " native_") || line.contains(" " + player + " died at ")) {
                    mine.add(line.strip());
                }
            }
            for (String line : mine.subList(Math.max(0, mine.size() - LOG_LINES), mine.size())) out.add(line);
        } catch (IOException ignored) {
            // No log to read: the replay goes without its lines.
        }
        return out;
    }

    private static JsonArray array(int... values) {
        JsonArray out = new JsonArray();
        for (int v : values) out.add(v);
        return out;
    }

    /** Waits a little for the files still being written. */
    @Override
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
