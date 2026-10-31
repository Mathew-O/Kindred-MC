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
                            BlockRotation.NONE, ticks + 600, 0L, true, false, 1, 1, true, context -> play(context, replay, run, ticks)));
                }
            }
        } catch (Exception unreadable) {
            throw new IllegalStateException("A replay in " + REPLAYS + " could not be read", unreadable);
        }
        return tests;
    }

    /** One run of a replay: the player made from its state where it died, its goal given, the ticks run. */
    private static void play(TestContext context, JsonObject replay, int run, int ticks) {
        var world = context.getWorld();
        var server = world.getServer();
        JsonObject state = replay;
        var at = state.getAsJsonArray("at");
        Vec3d start = Vec3d.of(context.getAbsolutePos(BlockPos.ORIGIN)).add(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble());
        Path directory = server.getSavePath(WorldSavePath.ROOT).resolve("kindred-replay-" + UUID.randomUUID());
        ServerCommandSource source = server.getCommandSource().withWorld(world).withPosition(start);
        var runtime = new PlayerRuntime(server, directory, NativePlayerSaves::read, writer -> writer);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("edits.properties"), "autonomousEdits=true\nprotectionKnown=true\n");
        } catch (java.io.IOException error) {
            throw new RuntimeException("Replay edit policy could not be saved", error);
        }
        runtime.start();
        String player = state.get("player").getAsString();
        String name = player.substring(0, Math.min(player.length(), 10)) + "R" + run;
        UUID[] id = new UUID[2];
        Vec3d[] from = new Vec3d[1];
        int[] completed = {0};
        boolean[] died = {false}, stopped = {false};
        long[] began = {-1};
        boolean stuck = "stuck".equals(state.get("kind").getAsString());
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(runtime.isReady(), "Ready"))
                .createAndAdd(() -> runtime.createNative(source, name))
                .createAndAdd(() -> {
                    var statuses = runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(), "Established");
                    id[0] = statuses.getFirst().id();
                    id[1] = statuses.getFirst().nativeId();
                })
                .createAndAdd(() -> {
                    ServerPlayerEntity body = server.getPlayerManager().getPlayer(id[1]);
                    restore(body, state);
                    world.setTimeOfDay(state.get("timeOfDay").getAsLong());
                    runtime.observeRun(new RunObserver() {
                        @Override public void died(String p, ServerPlayerEntity b, String cause, String stage, String stance, String habits, String task) { died[0] = true; }
                        @Override public void ended(String p, String skill, String result, String label) { if ("COMPLETED".equals(result)) completed[0]++; }
                    });
                    var goal = SurvivalGoal.valueOf(state.get("goal").getAsString());
                    if (goal != SurvivalGoal.NONE) context.assertTrue(runtime.startGoal(source, id[0], goal) == 1, "Its goal, " + goal + ", accepted");
                    from[0] = body.getPos();
                    began[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    ServerPlayerEntity body = server.getPlayerManager().getPlayer(id[1]);
                    if (died[0] || body == null) {
                        if (!stopped[0]) {
                            stopped[0] = true;
                            runtime.stop();
                        }
                        throw new AssertionError("Replay " + run + ": died again, as it did (" + state.get("cause").getAsString() + ")");
                    }
                    context.assertTrue(context.getTick() - began[0] >= ticks, "Still running");
                    if (stuck) {
                        context.assertTrue(body.getPos().distanceTo(from[0]) > 8 || completed[0] > 0, "Replay " + run + ": stuck where it was");
                    }
                    runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
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
                    double ms = capture.get().nanosOnThread() / 1e6;
                    context.assertTrue(ms <= 50, "The capture took " + ms + " ms on the server thread, more than a tick");
                    try {
                        capture.get().written().get(10, TimeUnit.SECONDS);
                    } catch (Exception late) {
                        throw new AssertionError("The replay's files not written: " + late);
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
                        KindredMod.LOGGER.info("Replay capture fixture: {} ms on the server thread, {} KB", String.format(java.util.Locale.ROOT, "%.1f", ms),
                                Files.size(folder.resolve("replay.snbt")) / 1024);
                    } catch (java.io.IOException | com.mojang.brigadier.exceptions.CommandSyntaxException unreadable) {
                        throw new AssertionError("The replay does not read back: " + unreadable);
                    }
                    zombie[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
