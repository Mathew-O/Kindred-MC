package dev.kindred.fabric;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EmptyBlockView;

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
    private boolean warmed;

    DeathReplays(Path dir, Path log) {
        this.dir = dir;
        this.log = log;
    }

    Path dir() { return dir; }

    /** Once a second on the server thread: the player's vitals, the last sixteen seconds of them kept. */
    void note(String player, ServerPlayerEntity body, long tick) {
        // Once, a copy thrown away: the first death's capture then pays for no loading of its code on its tick.
        if (!warmed) {
            warmed = true;
            Scene.copy(body.getServerWorld(), body.getBlockPos());
        }
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
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long start = System.nanoTime(), cpuStart = threads.getCurrentThreadCpuTime();
        var world = body.getServerWorld();
        BlockPos feet = body.getBlockPos();
        BlockPos origin = feet.add(-WIDTH / 2, -HEIGHT / 2, -WIDTH / 2);
        // On the tick, only what the world holds is read: each block as it stands, the data of the few that have any,
        // and the mobs'. The structure is made from the copies off the tick, as vanilla's would be; vanilla's own,
        // which looks up a block entity and tests the shape of every block on the tick, took up to 71 ms under load.
        var scene = Scene.copy(world, origin);
        // On the tick only copies are taken; the state is turned into its file off it, the items' components with it.
        Map<String, Object> state = state(player, body, origin, tick, context);
        var registries = world.getRegistryManager();
        long spent = System.nanoTime() - start, cpu = threads.getCurrentThreadCpuTime() - cpuStart;
        Path folder = dir.resolve(player + "-" + tick);
        Future<?> written = writer.submit(() -> write(folder, scene, state, registries, player));
        latest = new Capture(folder, spent, cpu, written);
        KindredMod.LOGGER.info("{} death replay captured in {} ms on the server thread ({} ms of its time)", player,
                String.format(java.util.Locale.ROOT, "%.1f", spent / 1e6), String.format(java.util.Locale.ROOT, "%.1f", cpu / 1e6));
        return java.util.Optional.of(latest);
    }

    /** The last capture taken, for a fixture to time and read back. */
    java.util.Optional<Capture> latestForTest() { return java.util.Optional.ofNullable(latest); }

    /** A capture: where its files go, its time on the server thread by the clock and in the thread's own CPU time. */
    record Capture(Path folder, long nanosOnThread, long cpuNanosOnThread, Future<?> written) { }

    /** What the replay keeps of the player, copied on the server thread: plain values, and copies of what it held. */
    private Map<String, Object> state(String player, ServerPlayerEntity body, BlockPos origin, long tick, Context context) {
        var world = body.getServerWorld();
        var state = new java.util.LinkedHashMap<String, Object>();
        state.put("kind", context.kind());
        state.put("player", player);
        state.put("tick", tick);
        state.put("dimension", world.getRegistryKey().getValue().toString());
        state.put("origin", new int[] {origin.getX(), origin.getY(), origin.getZ()});
        state.put("size", new int[] {WIDTH, HEIGHT, WIDTH});
        state.put("at", new double[] {body.getX() - origin.getX(), body.getY() - origin.getY(), body.getZ() - origin.getZ()});
        state.put("yaw", body.getYaw());
        state.put("pitch", body.getPitch());
        // Its vitals ten seconds before (the oldest kept if it has not been about that long); at death its health is none.
        Vitals then = null;
        for (Vitals v : vitals.getOrDefault(player, new ArrayDeque<>())) if (v.tick() <= tick - BEFORE || then == null) then = v;
        if (then == null) then = new Vitals(tick, body.getMaxHealth(), 20, 5, body.getMaxAir(), 0);
        state.put("health", then.health());
        state.put("food", then.food());
        state.put("saturation", then.saturation());
        state.put("air", then.air());
        state.put("absorption", then.absorption());
        var effects = new ArrayList<Object[]>();
        for (var effect : body.getStatusEffects()) {
            effects.add(new Object[] {effect.getEffectType().getKey().map(key -> key.getValue().toString()).orElse("unknown"), effect.getAmplifier(), effect.getDuration()});
        }
        state.put("effects", effects);
        // What it held as it died, before vanilla drops it: every slot, armour and the off hand, copied whole.
        var inventory = body.getInventory();
        var items = new ArrayList<Object[]>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) items.add(new Object[] {slot, stack.copy()});
        }
        state.put("inventory", items);
        state.put("selected", inventory.selectedSlot);
        state.put("timeOfDay", world.getTimeOfDay() % 24_000);
        state.put("raining", world.isRaining());
        state.put("thundering", world.isThundering());
        state.put("difficulty", world.getDifficulty().getName());
        state.put("goal", context.goal());
        state.put("stage", context.stage());
        state.put("task", context.task());
        state.put("stance", context.stance());
        state.put("habits", context.habits());
        state.put("cause", context.cause());
        state.put("ticks", 1200);
        return state;
    }

    /** The state as its file holds it, off the tick: the items' copies encoded with their components. */
    @SuppressWarnings("unchecked")
    private static JsonObject json(Map<String, Object> state, com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        JsonObject json = new JsonObject();
        for (var entry : state.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "origin", "size" -> json.add(entry.getKey(), array((int[]) value));
                case "at" -> {
                    JsonArray at = new JsonArray();
                    for (double v : (double[]) value) at.add(v);
                    json.add("at", at);
                }
                case "effects" -> {
                    JsonArray effects = new JsonArray();
                    for (Object[] e : (List<Object[]>) value) {
                        JsonObject effect = new JsonObject();
                        effect.addProperty("id", (String) e[0]);
                        effect.addProperty("amplifier", (Integer) e[1]);
                        effect.addProperty("duration", (Integer) e[2]);
                        effects.add(effect);
                    }
                    json.add("effects", effects);
                }
                case "inventory" -> {
                    JsonArray items = new JsonArray();
                    for (Object[] held : (List<Object[]>) value) {
                        var encoded = ItemStack.CODEC.encodeStart(ops, (ItemStack) held[1]).result();
                        if (encoded.isEmpty()) continue;
                        JsonObject item = new JsonObject();
                        item.addProperty("slot", (Integer) held[0]);
                        item.add("stack", encoded.get());
                        items.add(item);
                    }
                    json.add("inventory", items);
                }
                default -> {
                    if (value instanceof Number number) json.addProperty(entry.getKey(), number);
                    else if (value instanceof Boolean flag) json.addProperty(entry.getKey(), flag);
                    else json.addProperty(entry.getKey(), String.valueOf(value));
                }
            }
        }
        return json;
    }

    private void write(Path folder, Scene scene, Map<String, Object> copied, net.minecraft.registry.DynamicRegistryManager registries, String player) {
        try {
            Files.createDirectories(folder);
            JsonObject state = json(copied, registries.getOps(JsonOps.INSTANCE));
            state.add("log", lines(player));
            String snbt = NbtHelper.toNbtProviderString(scene.templateNbt());
            Files.writeString(folder.resolve("replay.snbt"), snbt, StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("replay.json"), new GsonBuilder().setPrettyPrinting().create().toJson(state), StandardCharsets.UTF_8);
            KindredMod.LOGGER.info("{} death replay written to {} ({} KB)", player, folder, snbt.length() / 1024);
        } catch (IOException | RuntimeException failure) {
            KindredMod.LOGGER.warn("{} death replay not written to {}: {}", player, folder, failure.toString());
        }
    }

    /**
     * The ground round the player as it died: its blocks, the data of those that have any, and the mobs in it, copied
     * on the server thread; {@link #templateNbt} makes of them the structure file vanilla's {@code writeNbt} would.
     */
    record Scene(BlockState[] states, Map<Integer, NbtCompound> blockData, List<Mob> mobs) {
        record Mob(Vec3d at, NbtCompound nbt) { }

        static Scene copy(net.minecraft.server.world.ServerWorld world, BlockPos origin) {
            var states = new BlockState[WIDTH * HEIGHT * WIDTH];
            var blockData = new HashMap<Integer, NbtCompound>();
            var registries = world.getRegistryManager();
            var at = new BlockPos.Mutable();
            int i = 0;
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) for (int z = 0; z < WIDTH; z++, i++) {
                at.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                BlockState state = world.getBlockState(at);
                states[i] = state;
                if (state.hasBlockEntity()) {
                    var data = world.getBlockEntity(at);
                    if (data != null) blockData.put(i, data.createNbtWithId(registries));
                }
            }
            var mobs = new ArrayList<Mob>();
            var box = new Box(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + WIDTH, origin.getY() + HEIGHT, origin.getZ() + WIDTH);
            for (Entity entity : world.getOtherEntities(null, box, e -> !(e instanceof PlayerEntity))) {
                NbtCompound nbt = new NbtCompound();
                entity.saveNbt(nbt);
                mobs.add(new Mob(new Vec3d(entity.getX() - origin.getX(), entity.getY() - origin.getY(), entity.getZ() - origin.getZ()), nbt));
            }
            return new Scene(states, blockData, mobs);
        }

        /**
         * The structure as vanilla writes it: whole blocks first, then the rest, then those with data, each by height,
         * air left out (a test clears its area before it places its structure); the mobs; the size; the data version.
         */
        NbtCompound templateNbt() {
            var full = new ArrayList<Integer>();
            var other = new ArrayList<Integer>();
            var withData = new ArrayList<Integer>();
            for (int i = 0; i < states.length; i++) {
                BlockState state = states[i];
                if (state.isAir()) continue;
                if (blockData.containsKey(i)) withData.add(i);
                else if (!state.getBlock().hasDynamicBounds() && state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) full.add(i);
                else other.add(i);
            }
            var palette = new java.util.LinkedHashMap<BlockState, Integer>();
            NbtList blocks = new NbtList();
            for (List<Integer> group : List.of(full, other, withData)) {
                for (int i : group) {
                    int y = i / (WIDTH * WIDTH), x = i / WIDTH % WIDTH, z = i % WIDTH;
                    NbtCompound block = new NbtCompound();
                    block.put("pos", ints(x, y, z));
                    block.putInt("state", palette.computeIfAbsent(states[i], state -> palette.size()));
                    if (blockData.containsKey(i)) block.put("nbt", blockData.get(i));
                    blocks.add(block);
                }
            }
            NbtList paletteNbt = new NbtList();
            for (BlockState state : palette.keySet()) paletteNbt.add(NbtHelper.fromBlockState(state));
            NbtList entities = new NbtList();
            for (Mob mob : mobs) {
                NbtCompound entity = new NbtCompound();
                NbtList pos = new NbtList();
                pos.add(net.minecraft.nbt.NbtDouble.of(mob.at().x));
                pos.add(net.minecraft.nbt.NbtDouble.of(mob.at().y));
                pos.add(net.minecraft.nbt.NbtDouble.of(mob.at().z));
                entity.put("pos", pos);
                BlockPos block = BlockPos.ofFloored(mob.at());
                entity.put("blockPos", ints(block.getX(), block.getY(), block.getZ()));
                entity.put("nbt", mob.nbt());
                entities.add(entity);
            }
            NbtCompound nbt = new NbtCompound();
            nbt.put("blocks", blocks);
            nbt.put("palette", paletteNbt);
            nbt.put("entities", entities);
            nbt.put("size", ints(WIDTH, HEIGHT, WIDTH));
            return NbtHelper.putDataVersion(nbt);
        }

        private static NbtList ints(int... values) {
            NbtList list = new NbtList();
            for (int v : values) list.add(net.minecraft.nbt.NbtInt.of(v));
            return list;
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
