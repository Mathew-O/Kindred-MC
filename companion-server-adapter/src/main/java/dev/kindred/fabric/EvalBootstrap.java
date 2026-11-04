package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.eval.EvalRecord;
import dev.kindred.core.eval.EvalScenario;
import dev.kindred.persistence.StoredNativePlayer;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In plain terms: an evaluation run with no one at the console. Given the {@code KINDRED_EVAL} setting, once the server
 * has started it does what an operator would type: it spawns the starters, sets their goal and the edits switch,
 * sprints the ticks asked for, and when they have run writes what happened to {@code logs/eval-result.json} and
 * stops the server.
 *
 * Technical: F-26. Read by {@link KindredMod} alone; the runtime does not know an evaluation from a game. It acts through
 * the runtime methods the operator's commands call (the edits switch, the roster spawn, the goal) and vanilla's own
 * commands (the game rules, {@code /tick sprint}), sees deaths and ended tasks through {@link RunObserver}, and once a
 * second looks at each starter's inventory, dimension and run state for the stages it reached. Everything runs on the
 * server thread; the file is written at the end (a temporary file moved over it whole).
 */
final class EvalBootstrap implements RunObserver {
    private static final int SPAWN_GAP = 40, ESTABLISH_WAIT = 1200, LOOK = 20;
    /** How far round its own spot a starter's ground is looked for, in blocks. */
    private static final int SPAWN_SEARCH = 16;

    private final EvalScenario scenario;
    private final EvalRecord record;
    private final Path out;
    private final Runnable finish;
    /** Where the starters are spawned round, on the ground; the world's spawn when null. */
    private final net.minecraft.util.math.BlockPos origin;
    /**
     * Whether it sets the world's game rules (keep inventory, the daylight cycle). Not in a fixture: the test server runs
     * every test in one world with the daylight cycle off, and a rule one test set would change the others.
     */
    private final boolean worldRules;
    private final Map<String, Integer> stuckSeen = new HashMap<>();
    /** The rescues of each player already in the record, by their lines' count (F-29). */
    private final Map<String, Integer> rescuesSeen = new HashMap<>();
    private int phase, spawned;
    private long phaseAt = -1, startTick = -1, lastTick;
    private boolean finished;
    private String note = "";

    EvalBootstrap(EvalScenario scenario, Path out, Runnable finish, net.minecraft.util.math.BlockPos origin, boolean worldRules) {
        this.scenario = scenario;
        this.record = new EvalRecord(scenario);
        this.out = out;
        this.finish = finish;
        this.origin = origin;
        this.worldRules = worldRules;
    }

    /** The run {@code KINDRED_EVAL} asks for, or none; a setting that cannot be read is logged and no run is made. */
    static Optional<EvalBootstrap> fromEnvironment() {
        String text = System.getenv("KINDRED_EVAL");
        if (text == null || text.isBlank()) return Optional.empty();
        try {
            var scenario = EvalScenario.parse(text);
            KindredMod.LOGGER.info("Evaluation run: {} ({})", scenario.asText(), scenario.earned() ? "earned" : "staged");
            return Optional.of(new EvalBootstrap(scenario, Path.of("logs", "eval-result.json").toAbsolutePath(), null, null, true));
        } catch (IllegalArgumentException refused) {
            KindredMod.LOGGER.error("KINDRED_EVAL not understood, no evaluation run: {}", refused.getMessage());
            return Optional.empty();
        }
    }

    EvalScenario scenario() { return scenario; }

    boolean finished() { return finished; }

    /** Once a server tick, on the server thread, from the end of each tick. */
    void tick(MinecraftServer server, PlayerRuntime runtime) {
        if (finished || runtime == null) return;
        lastTick = server.getTicks();
        seed = server.getOverworld().getSeed();
        switch (phase) {
            case 0 -> { // The runtime's records loaded: set the world up as an operator would, then the starters one by one.
                if (!runtime.isReady()) return;
                runtime.observeRun(this);
                KindredMod.LOGGER.info("Evaluation run: edits {}, lessons {}, atlas {}, rescue {}, advice {}", scenario.edits() ? "on" : "off",
                        scenario.lessons() ? "on" : "off", scenario.atlas() ? "on" : "off", scenario.rescue() ? "on" : "off", scenario.advice() ? "on" : "off");
                runtime.setWorldEdits(server.getCommandSource(), scenario.edits());
                runtime.lessons(scenario.lessons());
                runtime.atlas(scenario.atlas());
                runtime.rescue(scenario.rescue());
                runtime.advice(scenario.advice());
                if (worldRules) {
                    command(server, "gamerule keepInventory " + (scenario.keep() == EvalScenario.Keep.INVENTORY));
                    command(server, "gamerule doDaylightCycle true");
                }
                next(1);
            }
            case 1 -> { // Each a few blocks from the last, on the ground at the world's spawn.
                if (lastTick - phaseAt < SPAWN_GAP) return;
                if (spawned < scenario.starters().size()) {
                    var base = origin != null ? origin : server.getOverworld().getSpawnPos();
                    var ground = spawnSpot(server, runtime, base.add(3 * spawned, 0, 3 * spawned));
                    String name = scenario.starters().get(spawned);
                    KindredMod.LOGGER.info("Evaluation run: spawn {} at {}", name, ground.toShortString());
                    runtime.spawnCanonicalRoster(server.getCommandSource().withPosition(net.minecraft.util.math.Vec3d.ofBottomCenter(ground)), name);
                    spawned++;
                    phaseAt = lastTick;
                    return;
                }
                next(2);
            }
            case 2 -> { // Every starter in the world before its goal is set; a starter that never comes is noted, and the run goes on.
                var here = runtime.nativeStatuses(Actor.console()).stream()
                        .filter(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()).toList();
                boolean all = scenario.starters().stream().allMatch(n -> here.stream().anyMatch(s -> s.name().equalsIgnoreCase(n)));
                if (!all && lastTick - phaseAt < ESTABLISH_WAIT) return;
                if (!all) note = "not every starter came into the world: " + here.stream().map(PlayerRuntime.NativeStatus::name).toList();
                if (scenario.goal() != EvalScenario.Goal.NONE) {
                    var goal = switch (scenario.goal()) {
                        case NETHER -> dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL;
                        case END -> dev.kindred.core.survival.SurvivalGoal.STRONGHOLD;
                        case DRAGON -> dev.kindred.core.survival.SurvivalGoal.DRAGON;
                        case NONE -> dev.kindred.core.survival.SurvivalGoal.NONE;
                    };
                    for (var status : here) {
                        if (scenario.starters().stream().noneMatch(n -> n.equalsIgnoreCase(status.name()))) continue;
                        KindredMod.LOGGER.info("Evaluation run: goal {} for {}", goal, status.name());
                        runtime.startGoal(server.getCommandSource(), status.id(), goal);
                    }
                }
                // The voice speaks through the strategist: either asked for with none configured is said, and the run goes on.
                if ((scenario.strategist() || scenario.voice()) && System.getenv("KINDRED_STRATEGIST") == null) {
                    note += (note.isEmpty() ? "" : "; ") + (scenario.strategist() ? "strategist" : "voice") + " asked for, the strategist not configured";
                }
                startTick = lastTick;
                if (scenario.sprint()) command(server, "tick sprint " + scenario.ticks());
                next(3);
            }
            case 3 -> { // Running: a look once a second, the end when its ticks have run.
                long run = lastTick - startTick;
                if (run % LOOK == 0) look(server, runtime);
                if (run % ATTENTION_REPORT == 0) attention(runtime, run);
                if (run >= scenario.ticks()) end(server, true);
            }
            default -> { }
        }
    }

    /** How often the shared thinking budget's use is put in the console: every ten game minutes. */
    private static final long ATTENTION_REPORT = 12_000;
    private dev.kindred.core.attention.SchedulerDiagnostics attentionBefore;

    /**
     * The thinking slices the players were given in the last ten game minutes, by kind, the ticks whose two
     * milliseconds ran out, and the time the slices took: a goal plans its next step only in its slice, so a step
     * waits on it.
     */
    private void attention(PlayerRuntime runtime, long run) {
        var now = runtime.schedulerDiagnostics();
        var before = attentionBefore;
        attentionBefore = now;
        if (before == null) return;
        var kinds = new java.util.TreeMap<String, Long>();
        now.slicesByKind().forEach((kind, n) -> kinds.put(kind.name(), n - before.slicesByKind().getOrDefault(kind, 0L)));
        long slices = now.totalSlicesStarted() - before.totalSlicesStarted();
        long nanos = now.totalOptionalNanos() - before.totalOptionalNanos();
        KindredMod.LOGGER.info("Evaluation run: thinking at tick {}: {} slices in {} ticks {}; slices refused for time {}; {} ms in all, {} us a slice; overruns {}",
                run, slices, ATTENTION_REPORT, kinds, now.softLimitExceededEvents() - before.softLimitExceededEvents(), nanos / 1_000_000,
                slices == 0 ? 0 : nanos / 1_000 / slices, now.overruns() - before.overruns());
    }

    /** The server is stopping before the ticks have run: what there is, marked incomplete. */
    void stopping(MinecraftServer server) {
        if (!finished && phase >= 3) end(server, false);
    }

    /**
     * The ground nearest {@code near} where a starter can be made, as the roster spawn takes it, looked for outward to
     * {@link #SPAWN_SEARCH} blocks: the spot itself first, so that a starter whose spot was taken is made where it always
     * was. In a jungle the ground at the spot was a trunk's top in its leaves, which the spawn refuses, and the run went
     * on without two of its three starters (seed 404 in each ten-seed run). The spot itself when nothing round it is taken.
     */
    private static net.minecraft.util.math.BlockPos spawnSpot(MinecraftServer server, PlayerRuntime runtime, net.minecraft.util.math.BlockPos near) {
        var world = server.getOverworld();
        for (int r = 0; r <= SPAWN_SEARCH; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                var ground = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, near.add(dx, 0, dz));
                if (runtime.nativeSpawnable(server.getCommandSource().withPosition(net.minecraft.util.math.Vec3d.ofBottomCenter(ground)))) return ground;
            }
        }
        return world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, near);
    }

    private void next(int to) {
        phase = to;
        phaseAt = lastTick;
    }

    private static void command(MinecraftServer server, String line) {
        KindredMod.LOGGER.info("Evaluation run: /{}", line);
        server.getCommandManager().executeWithPrefix(server.getCommandSource(), line);
    }

    private void look(MinecraftServer server, PlayerRuntime runtime) {
        for (String name : scenario.starters()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
            if (player == null || !player.isAlive()) continue;
            var inventory = player.getInventory();
            long at = runTick(lastTick);
            if (holds(inventory, WOODEN)) record.reached(name, "wooden_tool", at);
            if (holds(inventory, STONE)) record.reached(name, "stone_tools", at);
            if (holds(inventory, IRON)) record.reached(name, "iron_ingot", at);
            if (holds(inventory, Set.of(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE))) record.reached(name, "iron_pickaxe", at);
            if (holds(inventory, Set.of(Items.DIAMOND, Items.DIAMOND_PICKAXE, Items.DIAMOND_SWORD, Items.DIAMOND_AXE))) record.reached(name, "diamond", at);
            if (holds(inventory, Set.of(Items.OBSIDIAN))) record.reached(name, "obsidian", at);
            if (holds(inventory, Set.of(Items.BLAZE_ROD, Items.BLAZE_POWDER))) record.reached(name, "blaze_rods", at);
            if (holds(inventory, Set.of(Items.ENDER_EYE))) record.reached(name, "eyes", at);
            var dimension = player.getWorld().getRegistryKey();
            if (dimension == World.NETHER) record.reached(name, "nether_entered", at);
            if (dimension == World.END) record.reached(name, "end", at);
            if (dimension == World.END && server.getWorld(World.END) != null && server.getWorld(World.END).getEnderDragonFight() != null
                    && server.getWorld(World.END).getEnderDragonFight().hasPreviouslyKilled()) record.reached(name, "dragon", at);
            runtime.runState(name).ifPresent(state -> {
                int seen = stuckSeen.getOrDefault(name, 0);
                if (state.stuckSpells() > seen) {
                    for (int i = seen; i < state.stuckSpells(); i++) {
                        record.stuck(name, new EvalRecord.Stuck(at, state.at().getX(), state.at().getY(), state.at().getZ(), state.stuckLevel()));
                    }
                    stuckSeen.put(name, state.stuckSpells());
                }
                // The goal keeps its last fifty rescues; the record keeps each once, in order.
                int known = rescuesSeen.getOrDefault(name, 0), held = state.rescues().size();
                if (held < known) known = 0; // A new goal: its rescues start again.
                for (int i = known; i < held; i++) record.rescued(name, new EvalRecord.Rescue(at, state.rescues().get(i)));
                rescuesSeen.put(name, held);
                if (STRONGHOLD_FOUND.contains(state.endStage())) record.reached(name, "stronghold", at);
                record.tokens(name, state.modelTokens());
            });
        }
    }

    /** At the stronghold: its portal room sought, or found. */
    private static final Set<String> STRONGHOLD_FOUND = Set.of("PORTAL_ROOM", "DONE");
    private static final Set<Item> WOODEN = Set.of(Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SHOVEL, Items.WOODEN_SWORD, Items.WOODEN_HOE);
    private static final Set<Item> STONE = Set.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_SWORD, Items.STONE_HOE);
    /** An iron ingot, or what it was made into straight away. */
    private static final Set<Item> IRON = Set.of(Items.IRON_INGOT, Items.IRON_PICKAXE, Items.IRON_SWORD, Items.IRON_AXE, Items.IRON_SHOVEL,
            Items.IRON_CHESTPLATE, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.BUCKET, Items.WATER_BUCKET,
            Items.LAVA_BUCKET, Items.SHIELD, Items.FLINT_AND_STEEL);

    private static boolean holds(net.minecraft.entity.player.PlayerInventory inventory, Set<Item> items) {
        for (int slot = 0; slot < inventory.size(); slot++) if (items.contains(inventory.getStack(slot).getItem())) return true;
        return false;
    }

    @Override
    public void died(String player, ServerPlayerEntity body, String cause, String stage, String stance, String habits, String task) {
        var world = body.getServerWorld();
        var at = body.getBlockPos();
        record.died(player, new EvalRecord.Death(runTick(world.getServer().getTicks()), cause, at.getX(), at.getY(), at.getZ(), world.getLightLevel(at),
                world.getTimeOfDay() % 24_000, world.getRegistryKey().getValue().toString(), body.getHungerManager().getFoodLevel(),
                world.isSkyVisible(at.up()), stage, stance, habits, task));
    }

    @Override
    public void ended(String player, String skill, String state, String label) {
        if (!"COMPLETED".equals(state)) return;
        // A frame's obsidian is cast where it stands and never held.
        if ("native_cast".equals(skill)) record.reached(player, "obsidian", runTick(lastTick));
        if ("native_ignite_portal".equals(skill)) record.reached(player, "portal_lit", runTick(lastTick));
    }

    /** A server tick as ticks into the run: from when the goals were set, none before. */
    private long runTick(long serverTick) {
        return startTick < 0 ? 0 : Math.max(0, serverTick - startTick);
    }

    private void end(MinecraftServer server, boolean complete) {
        finished = true;
        long run = startTick < 0 ? 0 : lastTick - startTick;
        String build = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("kindred")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        String json = record.toJson(build + (note.isEmpty() ? "" : " (" + note + ")"), server.getOverworld().getSeed(), run, complete);
        try {
            Files.createDirectories(out.getParent());
            Path partial = out.resolveSibling(out.getFileName() + ".partial");
            Files.writeString(partial, json, StandardCharsets.UTF_8);
            Files.move(partial, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            KindredMod.LOGGER.info("Evaluation run: {} ticks run, result written to {}", run, out);
        } catch (IOException failure) {
            KindredMod.LOGGER.error("Evaluation run: the result could not be written to {}", out, failure);
        }
        if (finish != null) finish.run();
        else if (complete) server.stop(false);
    }

    /** For a fixture: a run on its own runtime, round {@code origin}, the file where it says, and what to do at the end in place of stopping. */
    static EvalBootstrap forTest(String scenario, Path out, Runnable finish, net.minecraft.util.math.BlockPos origin) {
        return new EvalBootstrap(EvalScenario.parse(scenario), out, finish, origin, false);
    }

    /** The world's seed, read on the server thread as the run goes. */
    private long seed;

    /** The record so far, as the file will hold it, for the watch page (E-48); read on the server thread. */
    @Override
    public String resultSoFar() {
        return record.toJson("running", seed, startTick < 0 ? 0 : lastTick - startTick, finished);
    }

    /** For a fixture: the stages and deaths recorded so far, as the file would hold them. */
    String recordForTest(MinecraftServer server) {
        return record.toJson("test", server.getOverworld().getSeed(), startTick < 0 ? 0 : lastTick - startTick, finished);
    }

    List<String> startersForTest() { return scenario.starters(); }
}
