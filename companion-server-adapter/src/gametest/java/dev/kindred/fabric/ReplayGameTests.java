package dev.kindred.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.test.CustomTestProvider;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.test.TestFunction;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * In plain terms: deaths from real runs played again. Every replay kept in the suite's resources is run three times,
 * from the ground it died in and the state it had, by the real runtime: it passes if the player is alive at the end. And
 * a GameTest that a death is captured within one server tick and reads back as a replay.
 *
 * Technical: F-27. {@link #replays()} is vanilla's generator ({@code @CustomTestProvider} in yarn 1.21.1; mojang's
 * {@code GameTestGenerator}), which {@code TestFunctions.register} calls on its own when Fabric registers this class from
 * the {@code fabric-gametest} entrypoint: each {@code data/kindred_gametest/gametest/replays/<id>.json} with its structure
 * {@code kindred_gametest:replays/<id>} gives three tests in a batch of their own. A replay is staged by definition: the
 * ground is the structure, the player is made from its state, with the fixtures' own runtime and edit policy.
 */
public final class ReplayGameTests implements FabricGameTest {
    static final String REPLAYS = "data/kindred_gametest/gametest/replays";
    /**
     * Each replay's runtime, with the server tick past which its test is over. A test that times out never reaches its
     * own stop, and its player lived on into later batches: it fell when its scene was cleared, respawned at the world's
     * spawn and kept acting (12 or 13 a run in the corpus gate on 7ec519c). A server tick listener stops each one as
     * soon as its test has ended, passed or failed, or its test's time is past: the test's finally.
     */
    private static final java.util.Map<PlayerRuntime, Long> LIVE = new java.util.concurrent.ConcurrentHashMap<>();
    /** Each replay runtime's test, to stop it as soon as its test has ended, passed or failed. */
    private static final java.util.Map<PlayerRuntime, net.minecraft.test.GameTestState> TESTS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Each replay's mobs held still until its player is in place: given their AI back however the test ended. */
    private static final java.util.Map<PlayerRuntime, java.util.List<net.minecraft.entity.mob.MobEntity>> HELD = new java.util.concurrent.ConcurrentHashMap<>();

    private static void release(PlayerRuntime runtime) {
        var mobs = HELD.remove(runtime);
        if (mobs != null) for (var mob : mobs) if (mob.isAlive()) mob.setAiDisabled(false);
    }

    /** The test a context belongs to: TestContext keeps it private, and its end is what a finally would follow. */
    private static net.minecraft.test.GameTestState testOf(TestContext context) {
        for (var field : TestContext.class.getDeclaredFields()) {
            if (field.getType() != net.minecraft.test.GameTestState.class) continue;
            try {
                field.setAccessible(true);
                return (net.minecraft.test.GameTestState) field.get(context);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean WATCHING = new java.util.concurrent.atomic.AtomicBoolean();

    /** Every server tick, any replay past its test's time is stopped: the finally of a test that timed out. */
    private static void watch() {
        if (WATCHING.compareAndSet(false, true))
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
                stopFinished(server.getTicks());
                restoreIfNoneLeft(server);
            });
    }

    /**
     * The server's difficulty, weather and time of day as they were before the replays now running set theirs, put back once the last
     * of them has ended. A replay sets both server-wide to its record; left so, the contract suite's later batches ran on
     * a replay's easy difficulty or in its rain, and a shield fixture and a crystal's blast fixture failed (CI on #164).
     */
    private static net.minecraft.world.Difficulty difficultyBefore;
    private static boolean rainingBefore, thunderingBefore;
    private static int clearBefore, rainBefore;
    private static long timeBefore;

    static void remember(net.minecraft.server.MinecraftServer server, net.minecraft.server.world.ServerWorld world) {
        if (!LIVE.isEmpty() || difficultyBefore != null) return;
        difficultyBefore = server.getSaveProperties().getDifficulty();
        rainingBefore = world.isRaining();
        thunderingBefore = world.isThundering();
        var properties = server.getSaveProperties().getMainWorldProperties();
        clearBefore = properties.getClearWeatherTime();
        rainBefore = properties.getRainTime();
        timeBefore = world.getTimeOfDay();
    }

    static void restoreIfNoneLeft(net.minecraft.server.MinecraftServer server) {
        if (!LIVE.isEmpty() || difficultyBefore == null) return;
        server.setDifficulty(difficultyBefore, true);
        server.getOverworld().setWeather(clearBefore, rainBefore, rainingBefore, thunderingBefore);
        server.getOverworld().setTimeOfDay(timeBefore);
        difficultyBefore = null;
    }

    private static void stopFinished(long tick) {
        for (var it = LIVE.entrySet().iterator(); it.hasNext(); ) {
            var live = it.next();
            var test = TESTS.get(live.getKey());
            if (live.getValue() >= tick && (test == null || !test.isCompleted())) continue;
            it.remove();
            TESTS.remove(live.getKey());
            release(live.getKey());
            if (live.getKey().running()) live.getKey().stop();
        }
    }

    /** Three tests for every replay in the suite's resources; none when there are none. */
    @CustomTestProvider
    public Collection<TestFunction> replays() {
        var tests = new ArrayList<TestFunction>();
        var folder = FabricLoader.getInstance().getModContainer("kindred_gametest").flatMap(mod -> mod.findPath(REPLAYS));
        if (folder.isEmpty() || !Files.isDirectory(folder.get())) return tests;
        try (var files = Files.list(folder.get())) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String id = file.getFileName().toString().replace(".json", "");
                JsonObject replay = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                int ticks = replay.has("ticks") ? replay.get("ticks").getAsInt() : 1200;
                for (int n = 1; n <= 3; n++) {
                    int run = n;
                    tests.add(new TestFunction("replay_" + id, "replaygametests." + id + "_" + n, "kindred_gametest:replays/" + id,
                            BlockRotation.NONE, ticks + 600, 0L, true, false, 1, 1, true, context -> play(context, replay, id, run, ticks)));
                }
            }
        } catch (Exception unreadable) {
            throw new IllegalStateException("A replay in " + REPLAYS + " could not be read", unreadable);
        }
        return tests;
    }

    /**
     * The replay's own server policy, in its own runtime's edits.properties: world edits on, and the dragon fight on only
     * for a replay whose recorded goal is the dragon, as its recording server had it (the goal is refused otherwise: all 26
     * of the corpus's dragon scenes failed "Its goal, DRAGON, accepted" in every run up to #157's gate). The fight is
     * the runtime's own setting, read from this file when it starts, so no other replay's runtime ever sees it on.
     */
    static String edits(JsonObject state) {
        boolean dragon = state.has("goal") && "DRAGON".equals(state.get("goal").getAsString());
        return "autonomousEdits=true\nprotectionKnown=true\n" + (dragon ? "dragonFight=true\n" : "");
    }

    /**
     * One run of a replay: the player made from its state where it died, its goal given, the ticks run. Staged: the
     * scene's mobs are held still (no AI) from the structure's placing until the player is in place, then all let go on
     * the same tick, so each run starts from the recorded positions; no mob is moved and none given a target. The
     * world's time of day, weather and difficulty are set to the record.
     */
    /** For fixtures: called with a replay's goal and what was dropped once its memory is put back, by the replay's player. */
    static final java.util.Map<String, java.util.function.BiConsumer<PortalRunCampaign, java.util.List<String>>> ON_RESTORE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** For fixtures: the replays' players whose scene's memory must be put back, or the replay fails at its start. */
    static final java.util.Set<String> MUST_RESTORE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The scene box's size along {@code axis} as recorded, or the recorder's own when the scene has none. */
    private static int sizeOf(JsonObject state, int axis, int otherwise) {
        return state.has("size") && state.get("size").isJsonArray() && state.getAsJsonArray("size").size() > axis
                ? state.getAsJsonArray("size").get(axis).getAsInt() : otherwise;
    }

    static void play(TestContext context, JsonObject replay, int run, int ticks) {
        play(context, replay, null, run, ticks);
    }

    /**
     * The name a replay's player is made under, at most 16 letters, digits and underscores, as a player's profile name
     * must be: one of its own for each scene ({@code scene}, its id) and run. With none, a scene staged by a fixture, it
     * is the recorded player's and tick's.
     */
    static String replayName(String scene, String player, long tick, int run) {
        // The scene's id, not its player and tick: two worlds' Kindred caught at tick 99080 were one name (F-27a).
        int key = scene != null ? scene.hashCode() : player.hashCode() * 31 + (int) tick;
        return "R" + Integer.toHexString(key & 0xfffffff) + "_" + run;
    }

    /** As {@link #play(TestContext, JsonObject, int, int)}, for the scene {@code scene} (its id; null for one staged). */
    static void play(TestContext context, JsonObject replay, String scene, int run, int ticks) {
        var world = context.getWorld();
        var server = world.getServer();
        JsonObject state = replay;
        var at = state.getAsJsonArray("at");
        // A test's own position is its structure block's, one below the structure, so the structure's corner is (0, 1, 0).
        BlockPos boxOrigin = context.getAbsolutePos(BlockPos.ORIGIN.up()).toImmutable();
        Vec3d start = Vec3d.of(boxOrigin).add(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble());
        Path directory = server.getSavePath(WorldSavePath.ROOT).resolve("kindred-replay-" + UUID.randomUUID());
        // Made where it can stand, the nearest such cell, then set where it died: one that died falling, swimming or
        // knocked back died where no player can be made ("Make room on solid ground").
        // Where it is made, chosen once its ground has settled (below).
        ServerCommandSource[] made = new ServerCommandSource[1];
        Vec3d[] settling = new Vec3d[1];
        int[] settled = {0};
        var runtime = new PlayerRuntime(server, directory, NativePlayerSaves::read, writer -> writer);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("edits.properties"), edits(state));
        } catch (java.io.IOException error) {
            throw new RuntimeException("Replay edit policy could not be saved", error);
        }
        runtime.start();
        watch();
        remember(server, world);
        LIVE.put(runtime, (long) server.getTicks() + ticks + 600 + 20);
        var test = testOf(context);
        if (test != null) TESTS.put(runtime, test);
        // The scene's mobs held still until the player is in it (below): with their AI on from the structure's placing,
        // they moved for the 40 ticks the ground settles and the seconds the player takes to be made, different each
        // run, and the player's first choice (fight this one, back off from that one) differed with them.
        var held = new java.util.concurrent.CopyOnWriteArrayList<net.minecraft.entity.mob.MobEntity>();
        HELD.put(runtime, held);
        // A name of its own for each replay and run: with one name for every replay of a player, the next batch's clashed
        // with a player a failed run had left in the world.
        String name = replayName(scene, state.get("player").getAsString(), state.get("tick").getAsLong(), run);
        UUID[] id = new UUID[2];
        Vec3d[] from = new Vec3d[1];
        int[] completed = {0};
        boolean[] died = {false}, stopped = {false};
        long[] began = {-1};
        boolean stuck = "stuck".equals(state.get("kind").getAsString());
        // A stuck spell kept by the watchdog (F-27a): out of it within the spell's budget, by its goal's own progress, or by
        // leaving the 8 blocks round where it was caught and staying out for the budget's second half. Moving alone was not
        // out: a loop walking between two places ("to the wall", "back the way it came") moved it 8 blocks and passed.
        boolean spell = "spell".equals(state.get("kind").getAsString());
        long[] outSince = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    context.assertTrue(runtime.isReady(), "Ready");
                    var box = new net.minecraft.util.math.Box(context.getAbsolutePos(BlockPos.ORIGIN)).stretch(DeathReplays.WIDTH, DeathReplays.HEIGHT, DeathReplays.WIDTH);
                    for (var mob : world.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, box, m -> !m.isAiDisabled())) {
                        mob.setAiDisabled(true);
                        held.add(mob);
                    }
                })
                // Its ground settled before a cell to make it in is chosen: a structure just placed settles for some
                // ticks, its gravel falling and its water spreading. The cell chosen at once was checked again by the
                // runtime as the player was made a few ticks on, no longer one to be made in by then, and the third
                // run of echo-50149 was never made ("Creation area is no longer available", CI run 387) (F-22c6).
                .createAndAdd(() -> {
                    var box = new net.minecraft.util.math.Box(context.getAbsolutePos(BlockPos.ORIGIN)).stretch(DeathReplays.WIDTH, DeathReplays.HEIGHT, DeathReplays.WIDTH);
                    boolean falling = !world.getEntitiesByClass(net.minecraft.entity.FallingBlockEntity.class, box, e -> true).isEmpty();
                    Vec3d cell = standNear(world, start);
                    settled[0] = !falling && cell.equals(settling[0]) ? settled[0] + 1 : 0;
                    settling[0] = cell;
                    context.assertTrue(settled[0] >= 40, "Its ground settled: " + (falling ? "gravel falling" : "the cell to make it in " + cell + " for " + settled[0] + " ticks"));
                    made[0] = server.getCommandSource().withWorld(world).withPosition(cell);
                })
                .createAndAdd(() -> runtime.createNative(made[0], name))
                .createAndAdd(() -> {
                    var statuses = runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(),
                            "Established: " + statuses + "; made from " + made[0].getPosition() + ", " + creationCells(world, made[0].getPosition()));
                    id[0] = statuses.getFirst().id();
                    id[1] = statuses.getFirst().nativeId();
                })
                .createAndAdd(() -> {
                    ServerPlayerEntity body = server.getPlayerManager().getPlayer(id[1]);
                    body.refreshPositionAndAngles(start.x, start.y, start.z, state.get("yaw").getAsFloat(), state.get("pitch").getAsFloat());
                    restore(body, state);
                    world.setTimeOfDay(state.get("timeOfDay").getAsLong());
                    // Its weather and difficulty as recorded (the corpus batches scenes that share them, F-27a).
                    if (state.has("raining")) {
                        boolean raining = state.get("raining").getAsBoolean(), thundering = state.has("thundering") && state.get("thundering").getAsBoolean();
                        world.setWeather(raining ? 0 : 24000, raining ? 24000 : 0, raining, thundering);
                    }
                    if (state.has("difficulty")) {
                        var difficulty = net.minecraft.world.Difficulty.byName(state.get("difficulty").getAsString());
                        if (difficulty != null) server.setDifficulty(difficulty, true);
                    }
                    release(runtime);
                    runtime.observeRun(new RunObserver() {
                        @Override public void died(String p, ServerPlayerEntity b, String cause, String stage, String stance, String habits, String task) { died[0] = true; }
                        @Override public void ended(String p, String skill, String result, String label) { if ("COMPLETED".equals(result)) completed[0]++; }
                    });
                    var goal = SurvivalGoal.valueOf(state.get("goal").getAsString());
                    if (goal != SurvivalGoal.NONE) context.assertTrue(runtime.startGoal(made[0], id[0], goal) == 1, "Its goal, " + goal + ", accepted");
                    // Its goal's own memory of the box, as the recorder kept it (F-27a): what it had set aside, given up and
                    // known. Put back into this replay's own goal, nothing else; what does not fit is dropped and logged.
                    if (state.has("memory") && state.get("memory").isJsonObject()) {
                        var campaign = runtime.campaignForTest(id[0]);
                        var dropped = campaign.restoreMemory(state.getAsJsonObject("memory"), boxOrigin, sizeOf(state, 0, DeathReplays.WIDTH),
                                sizeOf(state, 1, DeathReplays.HEIGHT), server.getTicks());
                        KindredMod.LOGGER.info("Replay {} of {}: its goal's memory put back, {} entries dropped{}", run, name, dropped.size(),
                                dropped.isEmpty() ? "" : ": " + String.join("; ", dropped.subList(0, Math.min(8, dropped.size()))));
                        var hook = ON_RESTORE.get(state.get("player").getAsString());
                        if (hook != null) hook.accept(campaign, dropped);
                        MUST_RESTORE.remove(state.get("player").getAsString());
                    }
                    from[0] = body.getPos();
                    began[0] = context.getTick();
                })
                // Its own step, changing nothing: a timed task that fails is tried again each tick until the test's time.
                .createAndAdd(() -> context.assertFalse(MUST_RESTORE.contains(state.get("player").getAsString()), "Its scene's memory put back"))
                .createAndAdd(() -> {
                    ServerPlayerEntity body = server.getPlayerManager().getPlayer(id[1]);
                    if (died[0] || body == null) {
                        if (!stopped[0]) {
                            stopped[0] = true;
                            runtime.stop();
                        }
                        throw new net.minecraft.test.GameTestException("Replay " + run + ": died again, as it did (" + state.get("cause").getAsString() + ")");
                    }
                    if (spell) {
                        // Its goal's own progress: something gained since the scene began.
                        if (runtime.goalProgressTick(id[0]) > 0) {
                            KindredMod.LOGGER.info("Replay {} of {}: out of its stuck spell, its goal gained something after {} ticks", run, name, context.getTick() - began[0]);
                            runtime.stop();
                            return;
                        }
                        boolean out = body.getPos().distanceTo(from[0]) > 8;
                        if (!out) outSince[0] = -1;
                        else if (outSince[0] < 0) outSince[0] = context.getTick();
                    }
                    context.assertTrue(context.getTick() - began[0] >= ticks, "Still running");
                    if (spell) {
                        if (outSince[0] >= 0 && context.getTick() - outSince[0] >= ticks / 2) {
                            KindredMod.LOGGER.info("Replay {} of {}: out of its stuck spell, out of its 8 blocks for the last {} ticks", run, name, context.getTick() - outSince[0]);
                            runtime.stop();
                            return;
                        }
                        throw new net.minecraft.test.GameTestException("Replay " + run + ": still in its stuck spell after " + ticks + " ticks"
                                + (outSince[0] >= 0 ? " (out of its 8 blocks only for the last " + (context.getTick() - outSince[0]) + ")" : ""));
                    }
                    if (stuck) {
                        context.assertTrue(body.getPos().distanceTo(from[0]) > 8 || completed[0] > 0, "Replay " + run + ": stuck where it was");
                    }
                    runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Where to make the player from: one cell west of the nearest cell to {@code at}, anywhere in the scene, where the runtime
     * makes a native player (a solid block under it, room, no mob and no water), the first of the cells round it the
     * runtime tries. A player that drowned in a flooded cave may have none in the scene at all: then it is made on the test
     * world's ground beside the scene, and moved.
     */
    /** What stands in each cell a player may be made in beside {@code from}, as the runtime checks them, for a failure's words. */
    private static String creationCells(net.minecraft.server.world.ServerWorld world, Vec3d from) {
        var cells = new ArrayList<String>();
        for (int[] offset : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos cell = BlockPos.ofFloored(Math.floor(from.x) + offset[0] + 0.5, from.y, Math.floor(from.z) + offset[1] + 0.5);
            var bounds = new net.minecraft.util.math.Box(cell.getX() + .2, from.y, cell.getZ() + .2, cell.getX() + .8, from.y + 1.8, cell.getZ() + .8);
            cells.add(cell.toShortString() + " " + world.getBlockState(cell).getBlock().getTranslationKey().replace("block.minecraft.", "")
                    + (world.getFluidState(cell).isEmpty() ? "" : " fluid " + world.getFluidState(cell).getLevel())
                    + " on " + world.getBlockState(cell.down()).getBlock().getTranslationKey().replace("block.minecraft.", "")
                    + (world.isSpaceEmpty(null, bounds) ? "" : " not empty")
                    + world.getOtherEntities(null, bounds).stream().map(e -> " " + e.getType().getUntranslatedName()).reduce("", String::concat));
        }
        return "cells " + cells;
    }

    private static Vec3d standNear(net.minecraft.server.world.ServerWorld world, Vec3d at) {
        for (BlockPos cell : BlockPos.iterateOutwards(BlockPos.ofFloored(at), DeathReplays.WIDTH / 2 + 4, DeathReplays.HEIGHT, DeathReplays.WIDTH / 2 + 4)) {
            var bounds = new net.minecraft.util.math.Box(cell.getX() + .2, cell.getY(), cell.getZ() + .2, cell.getX() + .8, cell.getY() + 1.8, cell.getZ() + .8);
            if (world.isSpaceEmpty(null, bounds) && world.getOtherEntities(null, bounds).isEmpty() && world.getFluidState(cell).isEmpty()
                    && world.getBlockState(cell.down()).isSolidBlock(world, cell.down())) {
                return Vec3d.ofBottomCenter(cell.west());
            }
        }
        return at;
    }

    /** Its state as the replay holds it: where it stood, its vitals ten seconds before, what it held, its effects. */
    static void restore(ServerPlayerEntity body, JsonObject state) {
        var world = body.getServerWorld();
        var ops = world.getRegistryManager().getOps(JsonOps.INSTANCE);
        var inventory = body.getInventory();
        inventory.clear();
        for (var element : state.getAsJsonArray("inventory")) {
            var item = element.getAsJsonObject();
            ItemStack.CODEC.parse(ops, item.get("stack")).result().ifPresent(stack -> inventory.setStack(item.get("slot").getAsInt(), stack));
        }
        inventory.selectedSlot = state.get("selected").getAsInt();
        body.clearStatusEffects();
        for (var element : state.getAsJsonArray("effects")) {
            var effect = element.getAsJsonObject();
            Registries.STATUS_EFFECT.getEntry(Identifier.of(effect.get("id").getAsString())).ifPresent(entry ->
                    body.addStatusEffect(new StatusEffectInstance(entry, effect.get("duration").getAsInt(), effect.get("amplifier").getAsInt())));
        }
        body.setHealth(Math.max(1, state.get("health").getAsFloat()));
        body.getHungerManager().setFoodLevel(state.get("food").getAsInt());
        body.getHungerManager().setSaturationLevel(state.get("saturation").getAsFloat());
        body.setAir(state.get("air").getAsInt());
        body.setAbsorptionAmount(state.get("absorption").getAsFloat());
    }

    /**
     * A death captured within one server tick, its files written off the tick, and the capture read back as a replay:
     * the structure 33 by 17 by 33 with the mob that killed it, and its state with what it held and its vitals ten
     * seconds before. Staged: stone to stand on, a zombie with its AI off beside it, a stone pickaxe and bread; health 14
     * when its vitals are noted; then killed by the zombie.
     */
    /**
     * In plain terms: two scenes of the same player caught at the same tick in two worlds play as two players, never one
     * name for both.
     *
     * Technical: {@link #replayName} (F-27a). Named from the recorded player and tick alone, the corpus's
     * {@code e40a20-79a9aad-404-kindred-99080} and {@code diag-factions303-079bdc2-303-kindred-99080} were both made as
     * {@code R2d3924b_1}, in batches one after the other, and the two {@code 123080} scenes as one name in the same
     * batch; the report counted a player acting after its own batch in every run. Passes if each scene's name is its
     * own, the same scene's runs differ, and each is a valid profile name.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "replayNames", tickLimit = 20)
    public void twoScenesOfOnePlayerAtOneTickGetNamesOfTheirOwn(TestContext context) {
        String[][] pairs = {
                {"e40a20-79a9aad-404-kindred-99080", "diag-factions303-079bdc2-303-kindred-99080"},
                {"e40a20-79a9aad-20261030-kindred-123080", "e40a20-79a9aad-404-kindred-123080"}};
        long[] ticks = {99080, 123080};
        for (int i = 0; i < pairs.length; i++) {
            String a = replayName(pairs[i][0], "Kindred", ticks[i], 1), b = replayName(pairs[i][1], "Kindred", ticks[i], 1);
            context.assertTrue(!a.equals(b), "One name for two scenes: " + pairs[i][0] + " and " + pairs[i][1] + " both " + a);
            context.assertTrue(!a.equals(replayName(pairs[i][0], "Kindred", ticks[i], 2)), "One name for two runs: " + a);
            for (String name : new String[] {a, b}) {
                context.assertTrue(name.length() <= 16 && name.matches("[A-Za-z0-9_]+"), "Not a profile name: " + name);
            }
        }
        context.complete();
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "replayCapture", tickLimit = 1200)
    public void aDeathIsCapturedWithinOneTickAndReadsBackAsAReplay(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var replays = new DeathReplays(f.directory.resolve("replays"), null);
        ServerPlayerEntity[] dead = new ServerPlayerEntity[1];
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Replayed"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 5));
                    player.setHealth(14);
                    f.runtime.captureReplays(replays);
                    replays.note("Replayed", player, world.getServer().getTicks());
                    var made = net.minecraft.entity.EntityType.ZOMBIE.create(world);
                    zombie[0] = made;
                    made.refreshPositionAndAngles(player.getX() + 2, player.getY(), player.getZ(), 0, 0);
                    made.setPersistent();
                    made.setAiDisabled(true);
                    made.setInvulnerable(true);
                    world.spawnEntity(made);
                })
                // Retried until the blow lands: a player that has just joined takes no damage for a moment.
                .createAndAdd(() -> {
                    dead[0] = f.player();
                    dead[0].damage(context.getWorld().getDamageSources().mobAttack(zombie[0]), 1000f);
                    context.assertTrue(!dead[0].isAlive(), "Killed by the zombie");
                })
                .createAndAdd(() -> {
                    var capture = replays.latestForTest();
                    context.assertTrue(capture.isPresent(), "No capture at its death");
                    // The capture's own work on the server thread, in its CPU time: its wall time also counts the pauses of
                    // a collector or of other servers on the same cores, which no capture causes.
                    double ms = capture.get().nanosOnThread() / 1e6, cpu = capture.get().cpuNanosOnThread() / 1e6;
                    context.assertTrue(cpu <= 50, "The capture took " + cpu + " ms of the server thread's time, more than a tick (" + ms + " ms by the clock)");
                    try {
                        capture.get().written().get(10, TimeUnit.SECONDS);
                    } catch (Exception late) {
                        throw new net.minecraft.test.GameTestException("The replay's files not written: " + late);
                    }
                    Path folder = capture.get().folder();
                    try {
                        JsonObject state = JsonParser.parseString(Files.readString(folder.resolve("replay.json"))).getAsJsonObject();
                        context.assertTrue("death".equals(state.get("kind").getAsString()) && "Replayed".equals(state.get("player").getAsString()), "Its kind and player: " + state);
                        context.assertTrue(state.get("health").getAsFloat() == 14f, "Its vitals ten seconds before: " + state.get("health"));
                        context.assertTrue(state.get("inventory").toString().contains("stone_pickaxe"), "What it held: " + state.get("inventory"));
                        context.assertTrue(state.get("cause").getAsString().contains("Zombie"), "Its cause: " + state.get("cause"));
                        var template = new StructureTemplate();
                        var nbt = NbtHelper.fromNbtProviderString(Files.readString(folder.resolve("replay.snbt")));
                        template.readNbt(context.getWorld().getRegistryManager().getWrapperOrThrow(RegistryKeys.BLOCK), nbt);
                        context.assertTrue(template.getSize().equals(new Vec3i(DeathReplays.WIDTH, DeathReplays.HEIGHT, DeathReplays.WIDTH)), "Its size: " + template.getSize());
                        context.assertTrue(nbt.getList("entities", 10).size() >= 1, "The zombie in it");
                        KindredMod.LOGGER.info("Replay capture fixture: {} ms of the server thread's time, {} ms by the clock, {} KB", String.format(java.util.Locale.ROOT, "%.1f", cpu),
                                String.format(java.util.Locale.ROOT, "%.1f", ms), Files.size(folder.resolve("replay.snbt")) / 1024);
                    } catch (java.io.IOException | com.mojang.brigadier.exceptions.CommandSyntaxException unreadable) {
                        throw new net.minecraft.test.GameTestException("The replay does not read back: " + unreadable);
                    }
                    zombie[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A scene made here as the recorder writes one, in a bedrock pen on the field: the player at (16, 4, 16) in the box with
     * bread, a jungle log three high four east, day, the portal goal; with {@code memory} when given (STAGED).
     */
    private static JsonObject pennedScene(TestContext context, String player, long tick, String memory) {
        BlockPos box = context.getAbsolutePos(BlockPos.ORIGIN.up());
        BlockPos feet = box.add(16, 4, 16);
        var world = context.getWorld();
        FixtureGround.keep(context, feet.add(-8, -3, -8), feet.add(8, 8, 8));
        for (BlockPos c : BlockPos.iterate(feet.add(-8, -3, -8), feet.add(8, 8, 8))) {
            int dx = c.getX() - feet.getX(), dy = c.getY() - feet.getY(), dz = c.getZ() - feet.getZ();
            boolean wall = Math.max(Math.abs(dx), Math.abs(dz)) == 6 && dy <= 4, floor = Math.max(Math.abs(dx), Math.abs(dz)) <= 6 && dy < 0;
            world.setBlockState(c, (wall || floor ? net.minecraft.block.Blocks.BEDROCK : net.minecraft.block.Blocks.AIR).getDefaultState());
        }
        for (int dy = 0; dy < 3; dy++) world.setBlockState(feet.add(4, dy, 0), net.minecraft.block.Blocks.JUNGLE_LOG.getDefaultState());
        String json = "{\"kind\":\"death\",\"player\":\"" + player + "\",\"tick\":" + tick + ",\"size\":[33,17,33],\"at\":[16.5,4.0,16.5],"
                + "\"yaw\":-90,\"pitch\":0,\"health\":20,\"food\":20,\"saturation\":5,\"air\":300,\"absorption\":0,\"effects\":[],"
                + "\"inventory\":[{\"slot\":0,\"stack\":{\"id\":\"minecraft:bread\",\"count\":8}}],\"selected\":0,\"timeOfDay\":1000,"
                + "\"raining\":false,\"thundering\":false,\"difficulty\":\"peaceful\",\"goal\":\"NETHER_PORTAL\",\"stage\":\"staged\","
                + "\"task\":\"nothing\",\"stance\":\"PRESS_ON\",\"habits\":\"[]\",\"cause\":\"staged\",\"ticks\":300"
                + (memory == null ? "" : ",\"memory\":" + memory) + "}";
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * In plain terms: a replay of a scene whose goal had set a tree aside starts with that tree still set aside, as the
     * player had it, not with a fresh goal that would walk straight to it.
     *
     * Technical: F-27a, the harness half of the goal's memory. STAGED: a scene made as the recorder writes one (above), its
     * memory setting the tree's three logs aside; passes if the replay's goal has each set aside once its memory is put
     * back (a replay that puts back nothing fails at its start), nothing dropped, and the player lives the scene's 300 ticks.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "replayMemory", tickLimit = 1400)
    public void aScenesMemoryIsPutBackIntoItsReplaysGoal(TestContext context) {
        String memory = "{\"version\":1,\"blocked\":[{\"at\":[20,4,16],\"left\":1000000},{\"at\":[20,5,16],\"left\":1000000},{\"at\":[20,6,16],\"left\":1000000}]}";
        var scene = pennedScene(context, "Minded", 4242, memory);
        BlockPos log = context.getAbsolutePos(BlockPos.ORIGIN.up()).add(20, 4, 16).toImmutable();
        MUST_RESTORE.add("Minded");
        ON_RESTORE.put("Minded", (campaign, dropped) -> {
            ON_RESTORE.remove("Minded");
            for (int dy = 0; dy < 3; dy++) context.assertTrue(campaign.blocked(log.up(dy)), "The tree's log " + dy + " up set aside again");
            context.assertTrue(dropped.isEmpty(), "Nothing dropped: " + dropped);
        });
        play(context, scene, 1, 300);
    }

    /**
     * In plain terms: a scene recorded before replays kept their goal's memory plays as it always did.
     *
     * Technical: F-27a. STAGED as above with no {@code memory}: passes if nothing is put back and the player lives the
     * scene's 300 ticks, as every scene in the corpus so far.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "replayNoMemory", tickLimit = 1400)
    public void aSceneWithoutMemoryPlaysAsBefore(TestContext context) {
        var scene = pennedScene(context, "Plain", 4343, null);
        ON_RESTORE.put("Plain", (campaign, dropped) -> {
            ON_RESTORE.remove("Plain");
            throw new net.minecraft.test.GameTestException("A memory put back for a scene that has none");
        });
        play(context, scene, 1, 300);
    }
}
