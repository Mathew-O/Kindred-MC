package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.survival.PortalRunPlanner;
import dev.kindred.core.survival.PortalRunSnapshot;
import dev.kindred.core.survival.WorldFacts;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns the local portal-run plan into one concrete, bounded skill at a time for one AI player. Everything it
 * proposes still goes through the runtime's normal admission, paid activity, durable ledger and body checks;
 * this class only reads the player's own inventory and what it has seen.
 */
final class PortalRunCampaign {
    record Task(String skill, Map<String, String> args, String label) { }

    private static final double REACH = 4.3;
    /** Iron is most common around Y=16; coal in caves well above deepslate, which has very little. */
    private static final int IRON_TOP = 56, IRON_TARGET = 16, COAL_TARGET = 48;
    /** Branch mining: legs this long, side steps this wide (two solid blocks between legs, each wall seen). */
    private static final int BRANCH_LEG = 20, BRANCH_STEP = 3;
    /** Exploration stays within this many blocks of where the run started, so the player can find its way back. */
    private static final int EXPLORE_RADIUS = 192;
    private static final java.util.EnumSet<PortalRunPlanner.Action> TRAVEL = java.util.EnumSet.of(
            PortalRunPlanner.Action.GATHER_LOGS, PortalRunPlanner.Action.GATHER_STONE, PortalRunPlanner.Action.MINE_COAL,
            PortalRunPlanner.Action.MINE_IRON, PortalRunPlanner.Action.MINE_DIAMOND, PortalRunPlanner.Action.MAKE_OBSIDIAN,
            PortalRunPlanner.Action.FILL_WATER, PortalRunPlanner.Action.GATHER_FLINT);
    private static final int DIAMOND_TOP = -52, DIAMOND_TARGET = -58;

    private final WorldKnowledge knowledge = new WorldKnowledge();
    private final Set<Long> ownWorkstations = new HashSet<>();
    private final Map<Long, Long> blockedUntil = new HashMap<>();
    /** Targets the player could not walk to; consulted when choosing what to gather, never when digging. */
    private final Map<Long, Long> unreachableUntil = new HashMap<>();
    /** Where the player stood when each unreachable target failed: it stays unreachable from around there. */
    private final Map<Long, BlockPos> unreachableFrom = new HashMap<>();
    private BlockPos lastFeet = BlockPos.ORIGIN;
    private final Map<Long, Integer> failures = new HashMap<>();
    private final Map<Long, Integer> approaches = new HashMap<>();
    private final Map<Task, Integer> repeatFailures = new HashMap<>();
    private final Map<Task, Long> setAside = new HashMap<>();
    private Direction digDirection = Direction.NORTH;
    private Direction exploreHeading;
    private int exploreFailures;
    private BlockPos exploredFrom;
    private int exploreStalls;
    private int cutAdvances;
    private ObsidianSite obsidianSite;
    private int pourWaits;
    private PortalSite portalSite;
    private BlockPos dropSite;
    private java.util.UUID foe;
    private final Map<java.util.UUID, Long> ignoredFoes = new HashMap<>();
    private long foeUntil;
    private BlockPos home;
    /** 4×4×4 cells of cave the player has already walked through; exploration heads for the rest. */
    private final Set<Long> visitedCaves = new HashSet<>();
    /** Where tunnelling has already advanced: walking an old corridor back and forth is not progress. */
    private final Set<Long> tunnelled = new HashSet<>();
    /** A run from a creeper found no way out: until then, wall it off or fight it instead. */
    private long runFailedUntil;
    private int branchRun;
    private boolean branchSide, branchStepping;
    /** Knowledge sweeps to wait for before digging: the first look at goal start, then one after each cave stride. */
    private int lookUntilSweep = 1;
    private long dropSiteUntil;
    private String progress = "Starting";
    private PortalRunPlanner.Stage stage = PortalRunPlanner.Stage.WOOD;
    private long lastTick;
    private EditPolicy.ServerPolicy policy = EditPolicy.ServerPolicy.deferredD4();

    /** Lava source L to convert, the air cell W to pour water into, and the stand cell S beyond it. */
    private record ObsidianSite(BlockPos lava, BlockPos water, BlockPos stand) { }

    /** Origin is the frame's bottom-left corner; the frame spans origin..origin+3 along {@code axis}, five tall. */
    private record PortalSite(BlockPos origin, Direction along, Direction toward, BlockPos stand) {
        BlockPos at(int across, int up) { return origin.offset(along, across).up(up); }
        /** The cell between the player and the frame plane, which must be open to reach the frame. */
        BlockPos front(int across, int up) { return at(across, up).offset(toward.getOpposite()); }
    }

    String progress() { return progress; }
    PortalRunPlanner.Stage stage() { return stage; }
    WorldKnowledge knowledge() { return knowledge; }

    /** Bounded perception each tick; cheap enough to run while other work proceeds. */
    void observe(ServerPlayerEntity player) {
        knowledge.scan(player);
    }

    void taskFinished(Task task, boolean completed, long tick) {
        if (task != null && task.label().startsWith("Recover") && dropSite != null && completed) {
            dropSiteUntil = Math.min(dropSiteUntil, tick + 600); // One more look around, then move on.
        }
        if (task != null && completed && "native_bucket".equals(task.skill()) && "pour".equals(task.args().get("mode"))) {
            BlockPos poured = position(task.args());
            if (poured != null) knowledge.remember(WorldKnowledge.Kind.WATER, poured); // It knows where it poured.
        }
        if (task != null && completed && task.label().startsWith("Cut through") && task.label().endsWith("advance") && ++cutAdvances >= 4) {
            exploreStalls = 0; // A few blocks on: try walking again.
            cutAdvances = 0;
        }
        if (task != null && completed && "native_goto".equals(task.skill()) && task.label().startsWith("Explore")) {
            exploredFrom = lastFeet; // Compared with where the player stands at the next plan.
        }
        if (task != null && completed && "native_goto".equals(task.skill()) && task.label().startsWith("Walk to")) {
            BlockPos reached = position(task.args());
            if (reached != null) approaches.merge(reached.asLong(), 1, Integer::sum);
        }
        if (task != null && completed && "native_goto".equals(task.skill()) && task.label().startsWith("Pick up ")
                && !task.label().equals("Pick up food") && position(task.args()) != null) {
            BlockPos at = position(task.args()); // A drop that keeps drifting off is let go after a few tries.
            if (approaches.merge(at.asLong(), 1, Integer::sum) >= 3) blockedUntil.put(at.asLong(), tick + 1200);
        }
        if (task != null && completed && task.label().startsWith("Explore the cave") && position(task.args()) != null) {
            BlockPos at = position(task.args());
            visitedCaves.add(BlockPos.asLong(at.getX() >> 2, at.getY() >> 2, at.getZ() >> 2));
            lookUntilSweep = knowledge.sweeps() + 2; // Finish the sweep under way, then one full look from here.
        }
        if (task != null && completed && task.label().startsWith("Branch mine") && task.label().endsWith("advance")) branchTurn();
        if (task != null && completed && task.label().endsWith(": advance") && position(task.args()) != null) {
            if (tunnelled.size() > 20_000) tunnelled.clear();
            tunnelled.add(position(task.args()).asLong());
        }
        if (task != null && completed) repeatFailures.remove(task);
        if (task != null && completed && ("native_cast".equals(task.skill()) || task.label().startsWith("Cast: fill"))) castFailedFrom = null;
        if (completed || task == null) return;
        // The same task failing again and again is set aside for a while, whatever it is, so the plan moves on.
        if (repeatFailures.merge(task, 1, Integer::sum) >= 3) {
            repeatFailures.remove(task);
            setAside.put(task, tick + 1200);
        }
        BlockPos pos = position(task.args());
        // An unreachable trunk or ore column usually stays unreachable; skip its neighbours too.
        if (pos != null) avoid(pos, "native_goto".equals(task.skill()) && task.label().startsWith("Walk to"), tick);
        if ("native_goto".equals(task.skill()) && task.label().startsWith("Explore")) {
            exploreFailures++;
            exploreHeading = null;
        }
        if (task.label().equals("Run from creeper")) runFailedUntil = tick + 200;
        if (task.label().startsWith("Recover")) dropSite = null;
        if (task.label().startsWith("Cut through")) exploreStalls = 0; // That way is closed too; walk and look again.
        // A foe in a hole or across a ravine cannot be reached; leave it be for a while rather than try forever.
        if ((task.label().startsWith("Close in") || task.label().startsWith("Fight")) && foe != null) {
            ignoredFoes.put(foe, tick + 600);
            foe = null;
        }
        if (task.label().startsWith("Dig") || task.label().startsWith("Tunnel") || task.label().startsWith("Climb")) digDirection = digDirection.rotateYClockwise();
        if (task.label().startsWith("Obsidian")) obsidianSite = null;
        if ("native_goto".equals(task.skill()) && (task.label().startsWith("Portal") || task.label().equals("Cast: stand in front of the frame site"))) {
            portalSite = null; // Its stand is out of reach: choose again, without that spot.
        }
        // Another spot may see the faces, or the lava, that this one could not.
        if ("native_cast".equals(task.skill()) || task.label().startsWith("Cast: fill")) castFailedFrom = lastFeet;
    }

    void placedWorkstation(BlockPos pos) { ownWorkstations.add(pos.asLong()); }

    /** Remembers where the player died, like a person would, so it can try once to pick its things back up. */
    void died(BlockPos pos, ServerWorld world, long tick, boolean killedByMob) {
        // Counters start again from an empty inventory: progress is measured afresh.
        best.clear();
        stuckClock = 0;
        stuckLevel = 0;
        relocate = null;
        obsidianSite = null;
        exploreHeading = null;
        approaches.clear();
        // Lava burns the drops; anything else stays on the ground for about five minutes. Whatever killed the
        // player is probably still standing over them, so a mob kill is not worth walking back into.
        boolean burned = world.getFluidState(pos).isIn(FluidTags.LAVA) || world.getBlockState(pos).isOf(Blocks.FIRE);
        dropSite = burned || killedByMob ? null : pos.toImmutable();
        dropSiteUntil = tick + 5_000;
    }

    /**
     * Skips a target that failed. Repeat failures at the same place back off longer, so a fast-ticking server
     * cannot make the player cycle through the same few unreachable targets.
     */
    private void avoid(BlockPos pos, boolean column, long tick) {
        int count = failures.merge(pos.asLong(), 1, Integer::sum);
        long until = tick + (2400L << Math.min(count - 1, 5));
        blockedUntil.merge(pos.asLong(), until, Math::max); // The exact target, whatever kind it was.
        if (!column) return;
        for (int dy = -6; dy <= 6; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long key = pos.add(dx, dy, dz).asLong();
            unreachableUntil.merge(key, until, Math::max);
            unreachableFrom.put(key, lastFeet);
        }
    }

    /** Skipped until its back-off ends and the player has moved well away from where the walk failed. */
    private boolean unreachable(BlockPos pos) {
        Long until = unreachableUntil.get(pos.asLong());
        if (until == null) return false;
        BlockPos from = unreachableFrom.get(pos.asLong());
        return until > lastTick || from != null && from.getSquaredDistance(lastFeet) < 8 * 8;
    }

    private boolean blocked(BlockPos pos) {
        Long until = blockedUntil.get(pos.asLong());
        return until != null && until > lastTick;
    }

    private static BlockPos position(Map<String, String> args) {
        try {
            if (!args.containsKey("x")) return null;
            return new BlockPos(Integer.parseInt(args.get("x")), Integer.parseInt(args.get("y")), Integer.parseInt(args.get("z")));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    // ------------------------------------------------------------------ planning

    // ------------------------------------------------------------------ progress watchdog

    /** Game time with no progress, spent on active work or with nothing feasible, before trying somewhere else. */
    static final int STUCK_TICKS = 3_600;
    private long stuckClock;
    private boolean lastActive;
    private int stuckLevel;
    private BlockPos relocate;
    private long relocateUntil;
    private double relocateBest;
    private boolean relocateDig;
    private PortalRunSnapshot lastSnapshot;
    private final Map<String, Integer> best = new HashMap<>();
    private final Set<Long> seenCells = new HashSet<>();

    /**
     * The next task, under a watchdog that catches any loop, whatever its cause (pacing a corridor, bouncing
     * between spots, a plan with nothing feasible): when the route makes no progress for {@link #STUCK_TICKS} of
     * game time spent working, the player leaves for somewhere else, as a player gives up on a spot. Progress is a
     * new stage, a new best in a counter the route needs, or ground it has never stood in. Fights, cover, the night
     * shelter, waiting for daylight and resting do not count as time stuck.
     */
    Optional<Task> next(NativePlayerBody body, EditPolicy.ServerPolicy policy, long tick) {
        if (lastActive && tick > lastTick) stuckClock += tick - lastTick;
        Optional<Task> planned = plan(body, policy, tick);
        // New ground restarts the clock; only material progress ends a move elsewhere and the run of attempts.
        boolean newGround = seenCells.size() < 100_000
                && seenCells.add(BlockPos.asLong(lastFeet.getX() >> 3, lastFeet.getY() >> 3, lastFeet.getZ() >> 3));
        if (gainedSomething()) {
            stuckClock = 0;
            stuckLevel = 0;
            relocate = null;
        } else if (newGround) {
            stuckClock = 0;
        }
        boolean active = planned.isEmpty() || !passive(planned.get());
        if (active) {
            if (relocate == null && stuckClock > STUCK_TICKS) startRelocating();
            Optional<Task> away = relocateStep(body);
            if (away.isPresent()) {
                progress = stageLabel(stage) + ": no progress here for a while - " + away.get().label();
                lastActive = true;
                return away;
            }
        }
        lastActive = active;
        if (stuckLevel > 0) progress += " (tried elsewhere " + stuckLevel + (stuckLevel == 1 ? " time)" : " times)");
        return planned;
    }

    /** Safety, shelter and honest waiting: time spent on these is not time stuck. */
    private static boolean passive(Task task) {
        String label = task.label();
        return label.equals("Wait for daylight") || label.equals("Rest to heal") || label.startsWith("Fight")
                || label.startsWith("Take cover") || label.startsWith("Run from") || label.startsWith("Back off")
                || label.startsWith("Close in") || label.startsWith("Seal the shelter") || label.startsWith("Dig in")
                || label.startsWith("Place a torch") || label.startsWith("Get out of the water");
    }

    /** A new stage, or a new best in a counter the route needs. */
    private boolean gainedSomething() {
        boolean better = false;
        PortalRunSnapshot s = lastSnapshot;
        if (s != null) {
            int[] values = {stage.ordinal(), s.pickaxeTier(), s.swordTier(), s.chestArmorTier(), Math.min(s.woodUnits(), 40),
                    Math.min(s.cobble(), PortalRunPlanner.COBBLE_TARGET), Math.min(s.coal(), 8), Math.min(s.rawIron() + s.ironIngots(), 24),
                    s.ironIngots(), s.diamonds(), s.obsidian(), s.flint(), s.flintAndSteel(), s.emptyBuckets() + s.waterBuckets()};
            for (int i = 0; i < values.length; i++) {
                Integer before = best.get("c" + i);
                if (before == null || values[i] > before) {
                    if (before != null) better = true;
                    best.put("c" + i, values[i]);
                }
            }
        }
        return better;
    }

    /** Pick somewhere else to work from: a new heading two dozen blocks off, and every third time home. */
    private void startRelocating() {
        stuckLevel++;
        stuckClock = 0;
        tunnelled.clear();
        Direction heading = digDirection;
        for (int i = 0; i < stuckLevel; i++) heading = heading.rotateYClockwise();
        relocate = stuckLevel % 3 == 0 && home != null && home.getSquaredDistance(lastFeet) > 16 * 16 ? home : lastFeet.offset(heading, 24);
        relocateUntil = lastTick + STUCK_TICKS;
        relocateBest = Double.MAX_VALUE;
        relocateDig = false;
        KindredMod.LOGGER.info("Goal made no progress for {} ticks near {}; trying {} (attempt {})", STUCK_TICKS,
                lastFeet.toShortString(), relocate.toShortString(), stuckLevel);
    }

    /** Walk toward the new place while that gets closer; once walking stalls, dig toward it. */
    private Optional<Task> relocateStep(NativePlayerBody body) {
        if (relocate == null) return Optional.empty();
        double distance = Math.sqrt(lastFeet.getSquaredDistance(relocate));
        if (lastTick > relocateUntil || distance < 4) {
            relocate = null;
            return Optional.empty();
        }
        if (!relocateDig && !blocked(relocate) && distance < relocateBest - 1) {
            relocateBest = distance;
            return Optional.of(go(relocate, 3, true, "try elsewhere, toward " + relocate.toShortString()));
        }
        relocateDig = true;
        int dx = relocate.getX() - lastFeet.getX(), dz = relocate.getZ() - lastFeet.getZ();
        digDirection = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        Optional<Task> dig = relocate.getY() > lastFeet.getY() + 2 ? climb(body, "dig toward elsewhere") : tunnel(body, "dig toward elsewhere");
        if (dig.isEmpty()) relocate = null; // Nothing works that way either; the next stuck spell tries another.
        return dig;
    }

    private Optional<Task> plan(NativePlayerBody body, EditPolicy.ServerPolicy policy, long tick) {
        lastTick = tick;
        lastFeet = body.entity().getBlockPos().toImmutable();
        if (exploredFrom != null) {
            exploreStalls = exploredFrom.getSquaredDistance(lastFeet) < 4 * 4 ? exploreStalls + 1 : 0;
            exploredFrom = null;
        }
        this.policy = policy;
        ServerPlayerEntity player = body.entity();
        if (underground(player) && visitedCaves.size() < 50_000) {
            visitedCaves.add(BlockPos.asLong(lastFeet.getX() >> 2, lastFeet.getY() >> 2, lastFeet.getZ() >> 2));
        }
        PortalRunSnapshot snapshot = snapshot(body);
        lastSnapshot = snapshot;
        WorldFacts facts = facts(player);
        stage = PortalRunPlanner.stage(snapshot, facts);
        var steps = new java.util.ArrayList<>(PortalRunPlanner.plan(snapshot, facts));
        // Opportunistic upkeep the plan does not order: water while a source is close, a sword before going deep.
        if (snapshot.emptyBuckets() > 0 && snapshot.waterBuckets() == 0 && obsidianSite == null
                && knowledge.nearest(player.getServerWorld(), WorldKnowledge.Kind.WATER, player.getBlockPos(), p -> !blocked(p) && p.getSquaredDistance(player.getBlockPos()) < 64 * 64).isPresent()) {
            steps.addFirst(new PortalRunPlanner.Step(PortalRunPlanner.Action.FILL_WATER, "", 1, stage, "Fill a bucket with water while it is close"));
        }
        // Coal in view is worth taking early, as players do: torches for the caves and fuel for the furnace.
        if (snapshot.coal() < 4 && stage.ordinal() <= PortalRunPlanner.Stage.DIAMOND.ordinal() && snapshot.pickaxeTier() >= 1
                && knowledge.nearest(player.getServerWorld(), WorldKnowledge.Kind.COAL_ORE, player.getBlockPos(),
                        p -> !blocked(p) && !unreachable(p) && p.getSquaredDistance(player.getBlockPos()) < 10 * 10).isPresent()) {
            steps.addFirst(new PortalRunPlanner.Step(PortalRunPlanner.Action.MINE_COAL, "", 1, stage, "Take the coal in view"));
        }
        if (home == null) home = player.getBlockPos().toImmutable();
        // Gravel or sand that fell into the head space suffocates within seconds: break it before anything else.
        Optional<Task> trapped = digOut(body);
        if (trapped.isPresent()) {
            progress = stageLabel(stage) + ": stuck in a wall - " + trapped.get().label();
            return trapped;
        }
        Optional<Task> room = makeRoom(player);
        if (room.isPresent()) {
            progress = stageLabel(stage) + ": inventory full - " + room.get().label();
            return room;
        }
        Optional<Task> defense = defend(body);
        if (defense.isPresent()) {
            progress = stageLabel(stage) + ": fight back - " + defense.get().label();
            return defense;
        }
        // Hurt with nothing after it: heal before going on (food in hand is eaten by the survival tasks).
        if (player.getHealth() < 10 && (player.getHungerManager().getFoodLevel() >= 18 || snapshot.edibleFood() > 0)) {
            progress = stageLabel(stage) + ": heal before going on";
            return Optional.of(new Task("native_wait", Map.of("seconds", "4"), "Rest to heal"));
        }
        // Underground in the dark: light it, so nothing spawns beside the player while it works.
        Optional<Task> light = light(body, snapshot);
        if (light.isPresent()) {
            progress = stageLabel(stage) + ": light the way - " + light.get().label();
            return light;
        }
        // Leaving to gather: take the table and an emptied furnace along, as a player would.
        if (TRAVEL.contains(steps.getLast().action())) {
            Optional<Task> pickup = pickUpWorkstation(body, snapshot);
            if (pickup.isPresent()) {
                progress = stageLabel(stage) + ": take workstations along - " + pickup.get().label();
                return pickup;
            }
        }
        // Nights on the surface belong to skeletons: get below ground, and leave surface jobs for daylight.
        boolean night = player.getServerWorld().isNight() && player.getServerWorld().getDimension().hasSkyLight();
        if (night && !underground(player)) {
            Optional<Task> dig = digDown(body, player.getBlockY() - 3, "Dig in for the night");
            if (dig.isPresent()) {
                progress = stageLabel(stage) + ": shelter for the night - " + dig.get().label();
                return dig;
            }
        }
        if (night && steps.getLast().action() == PortalRunPlanner.Action.GATHER_LOGS) {
            // Waiting out the night: close the way in first, so nothing walks or shoots down the staircase.
            Optional<Task> seal = underground(player) && player.isOnGround() ? seal(body) : Optional.empty();
            if (seal.isPresent()) {
                progress = stageLabel(stage) + ": shelter for the night - " + seal.get().label();
                return seal;
            }
            progress = stageLabel(stage) + ": wait for daylight before going up for logs";
            return Optional.of(new Task("native_wait", Map.of("seconds", "4"), "Wait for daylight"));
        }
        Optional<Task> recover = recoverDrops(player, tick);
        if (recover.isPresent()) {
            progress = stageLabel(stage) + ": pick up what was dropped at death - " + recover.get().label();
            return recover;
        }
        Optional<Task> loose = pickUpLoose(player);
        if (loose.isPresent()) {
            progress = stageLabel(stage) + ": " + loose.get().label();
            return loose;
        }
        for (PortalRunPlanner.Step step : steps) {
            if (night && step.action() == PortalRunPlanner.Action.HUNT && snapshot.edibleFood() > 0) continue;
            Optional<Task> task = realize(step, snapshot, body, policy);
            if (task.isPresent() && setAside.getOrDefault(task.get(), Long.MIN_VALUE) > tick) continue;
            if (task.isPresent()) {
                progress = stageLabel(stage) + ": " + step.why() + " - " + task.get().label();
                return task;
            }
        }
        // Nothing can be placed, dug or reached from standing water (a tunnel that flooded): wade out to dry ground.
        if (player.isTouchingWater()) {
            var dry = new NativeNavigator(player.getServerWorld()).nearestDryStand(player.getBlockPos(), 16, 4);
            if (dry.isPresent() && !blocked(dry.get())) {
                progress = stageLabel(stage) + ": get out of the water";
                return Optional.of(go(dry.get(), 0.8, false, "Get out of the water"));
            }
        }
        // Underground with every way on refused (liquid below, holes all round): climb a step and look again.
        if (underground(player)) {
            Optional<Task> up = climb(body, "Climb to find another way");
            if (up.isPresent()) {
                blockedUntil.merge(lastFeet.asLong(), lastTick + 2400, Math::max); // A dead end: do not step back down into it.
                progress = stageLabel(stage) + ": no way on from here - " + up.get().label();
                return up;
            }
        }
        // Stranded on a ledge or pillar with every way off a drop: take the shortest fall it can afford, as a player
        // jumps down rather than wait for ever.
        Optional<Task> drop = jumpDown(player);
        if (drop.isPresent()) {
            progress = stageLabel(stage) + ": no way on from here - " + drop.get().label();
            return drop;
        }
        progress = stageLabel(stage) + ": waiting for a feasible step";
        return Optional.empty();
    }

    /**
     * The shortest drop off the edge within two blocks, at most twelve blocks: onto solid ground if the player keeps
     * at least ten health after vanilla fall damage, or into water, which takes no fall damage; never onto lava or fire.
     */
    private Optional<Task> jumpDown(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        var navigator = new NativeNavigator(world);
        BlockPos feet = player.getBlockPos();
        BlockPos best = null;
        int bestDrop = Integer.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos edge = feet.add(dx, 0, dz);
            if ((dx == 0 && dz == 0) || !navigator.clear(edge) || navigator.standable(edge)) continue;
            for (int k = 1; k <= 24; k++) {
                BlockPos landing = edge.down(k);
                if (navigator.swimmable(landing)) {
                    if (k <= 12 && k < bestDrop && !blocked(landing)) { bestDrop = k; best = landing; }
                    break;
                }
                if (!navigator.passable(landing)) break; // Lava and fire are not passable either.
                if (navigator.standable(landing)) {
                    boolean affordable = k <= 12 && player.getHealth() - Math.max(0, k - 3) >= 10;
                    if (affordable && k > NativeNavigator.MAX_SAFE_DROP && k < bestDrop && !blocked(landing)) { bestDrop = k; best = landing; }
                    break;
                }
            }
        }
        if (best == null) return Optional.empty();
        return Optional.of(new Task("native_goto", coords(best, Map.of("range", "0.8", "partial", "false",
                "drop", String.valueOf(Math.min(12, bestDrop)))), "Jump down from the ledge"));
    }

    /** Stone, dirt and odds and ends the route never needs, beyond a small keep; cobblestone-like blocks keep 64. */
    private static final Set<Item> JUNK = Set.of(Items.ANDESITE, Items.GRANITE, Items.DIORITE, Items.TUFF, Items.CALCITE,
            Items.DRIPSTONE_BLOCK, Items.DIRT, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.SAND, Items.RED_SAND, Items.SANDSTONE,
            Items.RAW_COPPER, Items.ROTTEN_FLESH, Items.BONE, Items.ARROW, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
            Items.FEATHER, Items.SMOOTH_BASALT, Items.WHEAT_SEEDS);

    /**
     * A full inventory cannot take crafted tools or a diamond; drop surplus where the player stands, as a person
     * would. Items dropped stay in the world.
     */
    private Optional<Task> makeRoom(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        int free = 0, stoneTotal = 0, gravelTotal = 0;
        for (int s = 0; s < 36; s++) {
            ItemStack stack = inventory.getStack(s);
            if (stack.isEmpty()) free++;
            else if (stoneLike(stack)) stoneTotal += stack.getCount();
            else if (stack.isOf(Items.GRAVEL)) gravelTotal += stack.getCount();
        }
        if (free >= 3) return Optional.empty();
        // The biggest surplus stack goes first, so a few drops clear real room.
        int bestSlot = -1, bestCount = 0;
        for (int s = 0; s < 36; s++) {
            ItemStack stack = inventory.getStack(s);
            if (stack.isEmpty()) continue;
            int surplus = JUNK.contains(stack.getItem()) ? stack.getCount()
                    : stoneLike(stack) ? Math.min(stack.getCount(), stoneTotal - 64)
                    : stack.isOf(Items.GRAVEL) ? Math.min(stack.getCount(), gravelTotal - 8) : 0;
            if (surplus > bestCount) { bestCount = surplus; bestSlot = s; }
        }
        if (bestSlot < 0) return Optional.empty();
        return Optional.of(new Task("native_discard", Map.of("slot", String.valueOf(bestSlot), "count", String.valueOf(bestCount)),
                "Drop surplus " + Registries.ITEM.getId(inventory.getStack(bestSlot).getItem()).getPath().replace('_', ' ')));
    }

    private static boolean stoneLike(ItemStack stack) {
        return stack.isIn(ItemTags.STONE_TOOL_MATERIALS) || stack.isOf(Items.COBBLESTONE);
    }

    private Optional<Task> pickUpWorkstation(NativePlayerBody body, PortalRunSnapshot s) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (s.craftingTables() == 0) {
            var table = nearestWorkstation(player, Blocks.CRAFTING_TABLE, 6);
            if (table.isPresent() && breakableNow(body, table.get())) return Optional.of(breakAt(table.get(), "Pick up the crafting table"));
        }
        if (s.furnaces() == 0 && ItemMoves.count(player.getInventory(), Items.RAW_IRON) == 0) {
            var furnace = nearestWorkstation(player, Blocks.FURNACE, 6);
            if (furnace.isPresent() && world.getBlockEntity(furnace.get()) instanceof AbstractFurnaceBlockEntity block && block.isEmpty()
                    && breakableNow(body, furnace.get())) {
                return Optional.of(breakAt(furnace.get(), "Pick up the furnace"));
            }
        }
        return Optional.empty();
    }

    private Optional<Task> recoverDrops(ServerPlayerEntity player, long tick) {
        if (dropSite == null) return Optional.empty();
        var world = player.getServerWorld();
        if (tick > dropSiteUntil || !world.isChunkLoaded(dropSite) || player.getBlockPos().getSquaredDistance(dropSite) > 140 * 140) {
            dropSite = null;
            return Optional.empty();
        }
        var drops = world.getEntitiesByClass(ItemEntity.class, new Box(dropSite).expand(8), drop -> drop.isAlive() && !drop.isInLava());
        if (drops.isEmpty()) {
            dropSite = null;
            return Optional.empty();
        }
        ItemEntity nearest = drops.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElseThrow();
        return Optional.of(go(nearest.getBlockPos(), 0.8, true, "Recover dropped items near " + dropSite.toShortString()));
    }

    private static String stageLabel(PortalRunPlanner.Stage stage) {
        String name = stage.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    PortalRunSnapshot snapshot(NativePlayerBody body) {
        var player = body.entity();
        var inventory = player.getInventory();
        int logs = 0, planks = 0, sticks = 0, cobble = 0, coal = 0, rawIron = 0, iron = 0, diamonds = 0, flint = 0, gravel = 0,
                obsidian = 0, buckets = 0, water = 0, fns = 0, tables = 0, furnaces = 0, food = 0, pick = 0, sword = 0, chest = 0, shields = 0;
        for (ItemStack worn : player.getArmorItems()) {
            if (!worn.isEmpty() && Registries.ITEM.getId(worn.getItem()).getPath().endsWith("_chestplate")) chest = Math.max(chest, tier(worn.getItem()));
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            int n = stack.getCount();
            Item item = stack.getItem();
            if (stack.isIn(ItemTags.LOGS)) logs += n;
            else if (stack.isIn(ItemTags.PLANKS)) planks += n;
            else if (item == Items.STICK) sticks += n;
            else if (stack.isIn(ItemTags.STONE_TOOL_MATERIALS) || item == Items.COBBLESTONE) cobble += n;
            else if (item == Items.COAL || item == Items.CHARCOAL) coal += n;
            else if (item == Items.RAW_IRON) rawIron += n;
            else if (item == Items.IRON_INGOT) iron += n;
            else if (item == Items.DIAMOND) diamonds += n;
            else if (item == Items.FLINT) flint += n;
            else if (item == Items.GRAVEL) gravel += n;
            else if (item == Items.OBSIDIAN) obsidian += n;
            else if (item == Items.BUCKET || item == Items.LAVA_BUCKET) buckets += n; // Lava carried to a cast empties into it.
            else if (item == Items.WATER_BUCKET) water += n;
            else if (item == Items.FLINT_AND_STEEL) fns += n;
            else if (item == Items.CRAFTING_TABLE) tables += n;
            else if (item == Items.FURNACE) furnaces += n;
            else if (item == Items.SHIELD) shields += n; // The inventory's last slot is the off hand.
            if (stack.contains(DataComponentTypes.FOOD) && safeFood(stack)) food += n;
            pick = Math.max(pick, stack.isIn(ItemTags.PICKAXES) ? tier(item) : 0);
            sword = Math.max(sword, stack.isIn(ItemTags.SWORDS) ? tier(item) : 0);
            if (Registries.ITEM.getId(item).getPath().endsWith("_chestplate")) chest = Math.max(chest, tier(item));
        }
        BlockPos feet = player.getBlockPos();
        boolean tableNearby = nearestWorkstation(player, Blocks.CRAFTING_TABLE, 16).isPresent();
        Optional<BlockPos> furnacePos = nearestWorkstation(player, Blocks.FURNACE, 16);
        boolean furnaceSmelting = false;
        int furnaceOutput = 0;
        if (furnacePos.isPresent() && player.getServerWorld().getBlockEntity(furnacePos.get()) instanceof AbstractFurnaceBlockEntity furnace) {
            // Smelting only while it burns or has fuel waiting; input left in a cold, empty furnace needs fuel.
            boolean lit = player.getServerWorld().getBlockState(furnacePos.get()).get(net.minecraft.block.AbstractFurnaceBlock.LIT);
            furnaceSmelting = !furnace.getStack(0).isEmpty() && (lit || !furnace.getStack(1).isEmpty());
            furnaceOutput = furnace.getStack(2).getCount();
            if (furnace.getStack(0).isOf(Items.RAW_IRON)) rawIron += furnace.getStack(0).getCount();
            if (furnace.getStack(2).isOf(Items.IRON_INGOT)) iron += furnace.getStack(2).getCount();
        }
        // Obsidian already set into the frame still counts toward the ten.
        if (portalSite != null) {
            obsidian += frameObsidian(player.getServerWorld(), portalSite);
            cobble += frameCorners(player.getServerWorld(), portalSite); // Placed corners count toward the four too.
        }
        boolean framed = portalSite != null && portalComplete(player.getServerWorld(), portalSite);
        boolean lit = portalSite != null && player.getServerWorld().getBlockState(portalSite.at(1, 1)).isOf(Blocks.NETHER_PORTAL);
        return new PortalRunSnapshot(logs, planks, sticks, cobble, coal, rawIron, iron, diamonds, flint, gravel, obsidian, buckets, water,
                fns, tables, furnaces, food, pick, sword, chest, shields, tableNearby, furnacePos.isPresent(), furnaceSmelting, furnaceOutput,
                player.getHungerManager().getFoodLevel(), framed, lit);
    }

    private static boolean safeFood(ItemStack stack) {
        return !stack.isOf(Items.ROTTEN_FLESH) && !stack.isOf(Items.SPIDER_EYE) && !stack.isOf(Items.POISONOUS_POTATO)
                && !stack.isOf(Items.PUFFERFISH) && !stack.isOf(Items.CHICKEN) && !stack.isOf(Items.CHORUS_FRUIT);
    }

    static int tier(Item item) {
        String id = Registries.ITEM.getId(item).getPath();
        if (id.startsWith("netherite_")) return 5;
        if (id.startsWith("diamond_")) return 4;
        if (id.startsWith("iron_")) return 3;
        if (id.startsWith("stone_")) return 2;
        if (id.startsWith("wooden_") || id.startsWith("golden_")) return 1;
        return 0;
    }

    private Optional<BlockPos> nearestWorkstation(ServerPlayerEntity player, net.minecraft.block.Block block, int radius) {
        var kind = block == Blocks.CRAFTING_TABLE ? WorldKnowledge.Kind.CRAFTING_TABLE : WorldKnowledge.Kind.FURNACE;
        BlockPos feet = player.getBlockPos();
        return knowledge.nearest(player.getServerWorld(), kind, feet, p -> p.getSquaredDistance(feet) <= radius * radius
                && ownWorkstations.contains(p.asLong()) && !blocked(p) && !unreachable(p));
    }

    // ------------------------------------------------------------------ steps

    private Optional<Task> realize(PortalRunPlanner.Step step, PortalRunSnapshot s, NativePlayerBody body, EditPolicy.ServerPolicy policy) {
        var player = body.entity();
        return switch (step.action()) {
            case DONE -> Optional.empty();
            case COLLECT_SMELTED -> nearestWorkstation(player, Blocks.FURNACE, 16).flatMap(f -> atWorkstation(player, f,
                    new Task("native_smelt", coords(f, Map.of("action", "extract")), "Take smelted iron")));
            case HUNT -> hunt(player);
            case GATHER_LOGS -> gather(body, WorldKnowledge.Kind.LOG, 64, "logs")
                    .or(() -> underground(player) ? climb(body, "Climb toward the surface for logs")
                            .or(() -> home == null ? Optional.empty() : Optional.of(go(home, 3, true, "Head back up toward home")))
                            : explore(body, "logs"));
            // Stone is everywhere below: take what is close, otherwise dig for it rather than walk to a far cave wall.
            case GATHER_STONE -> gather(body, WorldKnowledge.Kind.STONE, 6, "stone").or(() -> digDown(body, player.getBlockY() - 6, "Dig for stone"))
                    .or(() -> search(body, "stone"));
            // Ore in view first, then caves it has seen, which show far more ore than a tunnel; digging comes last.
            case MINE_COAL -> gather(body, WorldKnowledge.Kind.COAL_ORE, oreRadius(player, 32), "coal ore").or(() -> gather(body, WorldKnowledge.Kind.LOG, 48, "logs for fuel"))
                    .or(() -> cave(body, "coal", COAL_TARGET)).or(this::lookAround)
                    // Coal is scarce deep down: climb toward where it is common before tunnelling for it.
                    .or(() -> player.getBlockY() < COAL_TARGET - 12 ? climb(body, "Climb toward coal") : Optional.<Task>empty())
                    .or(() -> tunnel(body, "Tunnel for coal"));
            case MINE_IRON -> gather(body, WorldKnowledge.Kind.IRON_ORE, oreRadius(player, 40), "iron ore")
                    .or(() -> cave(body, "iron", IRON_TARGET)).or(this::lookAround).or(() -> descendOrTunnel(body, IRON_TOP, IRON_TARGET, "iron"));
            case MINE_DIAMOND -> gather(body, WorldKnowledge.Kind.DIAMOND_ORE, oreRadius(player, 40), "diamond ore")
                    .or(() -> gather(body, WorldKnowledge.Kind.IRON_ORE, 6, "iron ore on the way"))
                    .or(() -> cave(body, "diamonds", DIAMOND_TARGET))
                    .or(this::lookAround).or(() -> descendOrTunnel(body, DIAMOND_TOP, DIAMOND_TARGET, "diamonds"));
            case CRAFT -> craft(body, step.item());
            case PLACE_TABLE -> placeItem(body, Items.CRAFTING_TABLE, "Place a crafting table");
            case PLACE_FURNACE -> placeItem(body, Items.FURNACE, "Place a furnace");
            case SMELT -> smelt(body);
            case AWAIT_SMELT -> Optional.of(new Task("native_wait", Map.of("seconds", "3"), "Wait by the furnace"));
            // Water just poured for obsidian guards the mining: mine what it hardened before taking it back.
            case FILL_WATER -> (obsidianSite != null
                    ? obsidian(body).or(() -> gather(body, WorldKnowledge.Kind.OBSIDIAN, 8, "obsidian", true)) : Optional.<Task>empty())
                    .or(() -> fillWater(body)).or(() -> search(body, "water"));
            case MAKE_OBSIDIAN -> gather(body, WorldKnowledge.Kind.OBSIDIAN, 32, "obsidian", true).or(() -> obsidian(body))
                    .or(() -> cave(body, "lava", DIAMOND_TARGET))
                    .or(this::lookAround).or(() -> descendOrTunnel(body, DIAMOND_TOP, DIAMOND_TARGET, "lava"));
            case GATHER_FLINT -> flint(body);
            case BUILD_PORTAL -> buildPortal(body);
            case IGNITE_PORTAL -> ignite(body);
            case CAST_PORTAL -> castPortal(body);
        };
    }

    /** Methods the campaign can carry out; the planner never chooses one outside this set. */
    private static final Set<WorldFacts.Method> METHODS = java.util.EnumSet.of(WorldFacts.Method.MINE_OBSIDIAN, WorldFacts.Method.CAST_OBSIDIAN);
    private static final Map<WorldFacts.Source, WorldKnowledge.Kind> SOURCES = Map.of(
            WorldFacts.Source.TREE, WorldKnowledge.Kind.LOG, WorldFacts.Source.STONE, WorldKnowledge.Kind.STONE,
            WorldFacts.Source.COAL_ORE, WorldKnowledge.Kind.COAL_ORE, WorldFacts.Source.IRON_ORE, WorldKnowledge.Kind.IRON_ORE,
            WorldFacts.Source.DIAMOND_ORE, WorldKnowledge.Kind.DIAMOND_ORE, WorldFacts.Source.LAVA, WorldKnowledge.Kind.LAVA,
            WorldFacts.Source.WATER, WorldKnowledge.Kind.WATER, WorldFacts.Source.GRAVEL, WorldKnowledge.Kind.GRAVEL,
            WorldFacts.Source.CAVE, WorldKnowledge.Kind.CAVE);
    private WorldFacts facts;
    private long factsTick = Long.MIN_VALUE;

    /**
     * What the planner may choose from: the nearest source of each kind the player has seen (not set aside), and
     * the methods available. Rebuilt once a second, so the plan follows the world as the player learns it.
     */
    private WorldFacts facts(ServerPlayerEntity player) {
        if (facts != null && lastTick - factsTick < 20) return facts;
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var nearest = new java.util.EnumMap<WorldFacts.Source, Integer>(WorldFacts.Source.class);
        for (var source : SOURCES.entrySet()) {
            knowledge.nearest(world, source.getValue(), feet, p -> !blocked(p))
                    .ifPresent(pos -> nearest.put(source.getKey(), (int) Math.round(Math.sqrt(pos.getSquaredDistance(feet)))));
        }
        facts = new WorldFacts(nearest, METHODS);
        factsTick = lastTick;
        return facts;
    }

    /** Underground, sight lines are shorter and every walk is through the dark: ore within two dozen blocks. */
    private static int oreRadius(ServerPlayerEntity player, int surfaceRadius) {
        return underground(player) ? 24 : surfaceRadius;
    }

    /**
     * Walk on into a cave it has seen, toward the depth where {@code what} is common, as players go caving: the
     * walls show ore a tunnel never would, and the ore becomes known as it comes into view. Cave cells already
     * walked are left for new ones; a cave leading well away from the wanted depth is not followed.
     */
    private Optional<Task> cave(NativePlayerBody body, String what, int targetY) {
        var world = body.entity().getServerWorld();
        BlockPos feet = lastFeet;
        BlockPos anchor = home == null ? feet : home;
        int gapNow = Math.abs(feet.getY() - targetY);
        // About eight blocks on is a good stride: far enough to open new views, near enough to stay on known ground.
        return knowledge.best(world, WorldKnowledge.Kind.CAVE,
                        pos -> Math.abs(Math.hypot(pos.getX() - feet.getX(), pos.getZ() - feet.getZ()) - 8) + 1.5 * Math.abs(pos.getY() - targetY),
                        pos -> !visitedCaves.contains(BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2))
                                && !blocked(pos) && !unreachable(pos) && pos.getSquaredDistance(feet) >= 4
                                && Math.hypot(pos.getX() - feet.getX(), pos.getZ() - feet.getZ()) <= 48
                                && Math.hypot(pos.getX() - anchor.getX(), pos.getZ() - anchor.getZ()) <= EXPLORE_RADIUS
                                && Math.abs(pos.getY() - targetY) < gapNow + 8)
                // A full route only: a cave floor seen below a ledge is reached by digging down, not by standing at the edge.
                .map(pos -> go(pos, 0.8, false, "Explore the cave toward " + what));
    }

    /** Arrived somewhere new: look around (one full knowledge sweep) before deciding there is nothing but digging. */
    private Optional<Task> lookAround() {
        if (knowledge.sweeps() >= lookUntilSweep) return Optional.empty();
        return Optional.of(new Task("native_wait", Map.of("seconds", "1"), "Look around"));
    }

    /**
     * Branch mining: after a leg of {@link #BRANCH_LEG} blocks, step {@link #BRANCH_STEP} to one side and run
     * the next leg back the other way, alternating sides so the legs zigzag across fresh stone.
     */
    private void branchTurn() {
        if (++branchRun < (branchStepping ? BRANCH_STEP : BRANCH_LEG)) return;
        branchRun = 0;
        digDirection = branchSide ? digDirection.rotateYCounterclockwise() : digDirection.rotateYClockwise();
        if (branchStepping) branchSide = !branchSide;
        branchStepping = !branchStepping;
    }

    /**
     * In the dark underground (no block light where it stands), place an owned torch, crafting four from its own
     * coal and a stick when it has none and coal to spare. Hostile mobs spawn only in darkness, so a torch every
     * dozen blocks keeps the way it works and walks clear of new spawns.
     */
    private Optional<Task> light(NativePlayerBody body, PortalRunSnapshot snapshot) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (!underground(player) || !player.isOnGround()) return Optional.empty();
        BlockPos feet = player.getBlockPos();
        if (world.getLightLevel(net.minecraft.world.LightType.BLOCK, feet) > 1) return Optional.empty();
        var inventory = player.getInventory();
        int torch = slotOf(body, Items.TORCH);
        if (torch < 0) {
            // Fuel for smelting and the sticks kept for pickaxes still to make come first.
            int coal = ItemMoves.count(inventory, Items.COAL) + ItemMoves.count(inventory, Items.CHARCOAL);
            int keptSticks = PortalRunPlanner.sticksKept(snapshot, PortalRunPlanner.frameMethod(snapshot, facts(player)));
            // About fifteen iron to smelt through the iron stage: two coal stay for the furnace until then.
            int keptCoal = stage.ordinal() <= PortalRunPlanner.Stage.IRON.ordinal() ? 2 : 0;
            if (coal <= keptCoal) return Optional.empty();
            if (snapshot.sticks() > keptSticks) return craft(body, "torch");
            return snapshot.planks() >= 2 ? craft(body, "stick") : Optional.empty();
        }
        if (torch >= 9) return Optional.of(swapToHotbar(body, torch, "Ready a torch"));
        for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            BlockPos spot = feet.offset(direction);
            if (!world.getBlockState(spot).isAir() || !world.getFluidState(spot).isEmpty() || blocked(spot)) continue;
            if (!world.getBlockState(spot.down()).isSideSolidFullSquare(world, spot.down(), Direction.UP)) continue;
            return Optional.of(new Task("native_place", coords(spot, Map.of("side", "up", "slot", String.valueOf(torch))),
                    "Place a torch at " + spot.toShortString()));
        }
        return Optional.empty();
    }

    private static Map<String, String> coords(BlockPos pos, Map<String, String> extra) {
        var args = new LinkedHashMap<String, String>();
        args.put("x", String.valueOf(pos.getX()));
        args.put("y", String.valueOf(pos.getY()));
        args.put("z", String.valueOf(pos.getZ()));
        args.putAll(extra);
        return Map.copyOf(args);
    }

    private static Task go(BlockPos target, double range, boolean partial, String label) {
        return new Task("native_goto", coords(target, Map.of("range", String.valueOf(range), "partial", String.valueOf(partial))), label);
    }

    private static Task breakAt(BlockPos pos, String label) {
        return new Task("native_break", coords(pos, Map.of()), label);
    }

    /**
     * A break whose drops wait on the ground: cells opened for a passage are walked through next anyway, and
     * chasing each drop would carry the player off the staircase or out of its working spot.
     */
    private static Task clearAt(BlockPos pos, String label) {
        return new Task("native_break", coords(pos, Map.of("collect", "false")), label);
    }

    private static double eyeDistance(ServerPlayerEntity player, BlockPos pos) {
        return player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
    }

    /** A block this player could break right now from where it stands. */
    /** Break the block the player's head (or feet) is stuck in, as a player digs out of gravel that fell on it. */
    private Optional<Task> digOut(NativePlayerBody body) {
        var player = body.entity();
        if (!player.isInsideWall()) return Optional.empty();
        var world = player.getServerWorld();
        for (BlockPos cell : List.of(BlockPos.ofFloored(player.getEyePos()), player.getBlockPos())) {
            if (world.getBlockState(cell).getCollisionShape(world, cell).isEmpty() || !breakableNow(body, cell)) continue;
            return Optional.of(clearAt(cell, "Dig out of the wall at " + cell.toShortString()));
        }
        return Optional.empty();
    }

    private boolean breakableNow(NativePlayerBody body, BlockPos pos) {
        return breakableNow(body, pos, false);
    }

    private boolean breakableNow(NativePlayerBody body, BlockPos pos, boolean guarded) {
        var player = body.entity();
        if (eyeDistance(player, pos) > REACH || !body.miningEligible(pos)) return false;
        if (RoamingEdits.breakDenial(player, pos, policy, guarded) != null) return false;
        return NativeBreakAction.bestTool(body, player.getServerWorld().getBlockState(pos)) != -2;
    }

    private boolean harvestable(NativePlayerBody body, BlockPos pos) {
        var state = body.entity().getServerWorld().getBlockState(pos);
        return NativeBreakAction.bestTool(body, state) != -2;
    }

    private Optional<Task> atWorkstation(ServerPlayerEntity player, BlockPos station, Task use) {
        if (eyeDistance(player, station) <= 4.0) return Optional.of(use);
        return Optional.of(go(station, 2.5, false, "Walk to the workstation"));
    }

    /**
     * Break the nearest seen block of a kind, walking into reach first and clearing whatever blocks the view
     * (leaves around a trunk, a dirt lip over an ore). A target that stays out of reach after two approaches
     * is skipped along with its column.
     */
    private Optional<Task> gather(NativePlayerBody body, WorldKnowledge.Kind kind, int radius, String what) {
        return gather(body, kind, radius, what, false);
    }

    private Optional<Task> gather(NativePlayerBody body, WorldKnowledge.Kind kind, int radius, String what, boolean guarded) {
        var player = body.entity();
        BlockPos feet = player.getBlockPos();
        BlockPos support = feet.down();
        Predicate<BlockPos> usable = p -> !blocked(p) && !unreachable(p) && !p.equals(support) && p.getSquaredDistance(feet) <= radius * radius
                && (kind != WorldKnowledge.Kind.OBSIDIAN || madeFromLava(player.getServerWorld(), p))
                && harvestable(body, p) && player.getServerWorld().canPlayerModifyAt(player, p)
                && (kind != WorldKnowledge.Kind.LOG || p.getY() <= feet.getY() + 4 && reachableFromGround(player.getServerWorld(), p));
        for (int candidate = 0; candidate < 4; candidate++) {
            var target = knowledge.nearest(player.getServerWorld(), kind, feet, usable);
            if (target.isEmpty()) return Optional.empty();
            BlockPos pos = target.get();
            if (breakableNow(body, pos, guarded)) {
                return Optional.of(new Task("native_break", coords(pos, Map.of("guarded", String.valueOf(guarded))),
                        "Mine " + what + " at " + pos.toShortString()));
            }
            var cover = obstruction(body, pos);
            if (cover.isPresent()) return Optional.of(breakAt(cover.get(), "Clear the way to " + what + " at " + pos.toShortString()));
            // Plans are re-evaluated often; only finished walks count as approaches.
            if (approaches.getOrDefault(pos.asLong(), 0) < 2) {
                return Optional.of(go(pos, 3.5, false, "Walk to " + what + " at " + pos.toShortString()));
            }
            approaches.remove(pos.asLong());
            avoid(pos, true, lastTick);
        }
        return Optional.empty();
    }

    /**
     * A log a player standing beside its trunk can reach: the trunk stands on solid ground no more than four
     * blocks below it. Logs left hanging after the trunk below was cut would need a pillar, so they are skipped.
     */
    private static boolean reachableFromGround(ServerWorld world, BlockPos log) {
        BlockPos below = log.down();
        for (int depth = 1; depth <= 4; depth++, below = below.down()) {
            BlockState state = world.getBlockState(below);
            if (state.isIn(BlockTags.LOGS)) continue;
            return !state.getCollisionShape(world, below).isEmpty() && !state.isIn(BlockTags.LEAVES);
        }
        return false;
    }

    /**
     * Obsidian that water and lava just made, still touching one of them, as opposed to somebody's build (or this
     * player's own portal frame), which is never mined for material.
     */
    private boolean madeFromLava(ServerWorld world, BlockPos pos) {
        if (portalSite != null) {
            for (int up = 0; up <= 4; up++) for (int across = 0; across <= 3; across++) if (portalSite.at(across, up).equals(pos)) return false;
        }
        for (Direction direction : Direction.values()) {
            var fluid = world.getFluidState(pos.offset(direction));
            if (fluid.isIn(FluidTags.LAVA) || direction != Direction.DOWN && fluid.isIn(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** The first block between the player's eyes and an in-reach target, when the player could break it now. */
    private Optional<BlockPos> obstruction(NativePlayerBody body, BlockPos pos) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (eyeDistance(player, pos) > REACH) return Optional.empty();
        var hit = world.raycast(new RaycastContext(player.getEyePos(), Vec3d.ofCenter(pos), RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(pos)) return Optional.empty();
        BlockPos first = hit.getBlockPos().toImmutable();
        if (blocked(first) || liquidNear(world, first) || !breakableNow(body, first)) return Optional.empty();
        return Optional.of(first);
    }

    /** Underground, search by tunnelling along the current level; on the surface, walk out and look. */
    private Optional<Task> search(NativePlayerBody body, String what) {
        var player = body.entity();
        if (underground(player)) return tunnel(body, "Tunnel for " + what).or(() -> explore(body, what));
        return explore(body, what).or(() -> tunnel(body, "Tunnel for " + what));
    }

    /**
     * Walk out to look, and when walking keeps ending where it started (hemmed in by leaves, trunks or a drop the
     * routes cannot cross), cut a two-high way through along the heading, as a person would.
     */
    private Optional<Task> explore(NativePlayerBody body, String what) {
        var player = body.entity();
        if (exploreStalls >= 2 && exploreHeading != null) {
            digDirection = exploreHeading;
            Optional<Task> cut = tunnel(body, "Cut through " + exploreHeading.asString() + " for " + what);
            if (cut.isPresent()) return cut;
        }
        return explore(player, what);
    }

    private static boolean underground(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        // Below the ground surface (leaves do not count as cover) with no open sky: a shaft or a cave, not a forest floor.
        return !world.isSkyVisible(feet.up()) && feet.getY() < world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) - 2;
    }

    /**
     * Head toward unexplored ground along a steady heading, turning when a heading keeps failing, would leave
     * the area around home, or ends on open water.
     */
    private Optional<Task> explore(ServerPlayerEntity player, String what) {
        var world = player.getServerWorld();
        if (exploreHeading == null) {
            Direction[] headings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
            exploreHeading = headings[Math.floorMod(exploreFailures + (int) (player.getUuid().getLeastSignificantBits() & 3), 4)];
        }
        BlockPos anchor = home == null ? player.getBlockPos() : home;
        for (int turn = 0; turn < 4; turn++, exploreHeading = exploreHeading.rotateYClockwise()) {
            BlockPos ahead = player.getBlockPos().offset(exploreHeading, 28);
            if (!world.isChunkLoaded(ahead)) ahead = player.getBlockPos().offset(exploreHeading, 12);
            if (!world.isChunkLoaded(ahead)) continue;
            if (Math.hypot(ahead.getX() - anchor.getX(), ahead.getZ() - anchor.getZ()) > EXPLORE_RADIUS) continue;
            int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ahead.getX(), ahead.getZ());
            boolean onSurface = player.getBlockY() > surface - 8;
            if (onSurface && !world.getFluidState(new BlockPos(ahead.getX(), surface - 1, ahead.getZ())).isEmpty()) continue;
            BlockPos target = new BlockPos(ahead.getX(), onSurface ? surface : player.getBlockY(), ahead.getZ());
            if (blocked(target) || unreachable(target)) continue;
            return Optional.of(go(target, 3, true, "Explore " + exploreHeading.asString() + " for " + what));
        }
        return Optional.empty();
    }

    private Optional<Task> descendOrTunnel(NativePlayerBody body, int top, int target, String what) {
        int y = body.entity().getBlockY();
        // Water or lava under every way down (an aquifer, a lava lake): tunnel on until the ground below is solid.
        if (y > Math.min(top, target + 3)) {
            return digDown(body, target, "Dig down toward " + what).or(() -> tunnel(body, "Tunnel past liquid toward " + what));
        }
        // At the richest depth with no ore or cave in view: branch mine, legs zigzagging through fresh stone.
        return tunnel(body, "Branch mine for " + what);
    }

    /**
     * Staircase one step down along {@code digDirection}: clear head, feet and step-down cells ahead, then step.
     * Every break is still a separate recorded action with the lava and support checks.
     */
    private Optional<Task> digDown(NativePlayerBody body, int targetY, String label) {
        var player = body.entity();
        if (player.getBlockY() <= targetY) return tunnel(body, label);
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (int turn = 0; turn < 4; turn++) {
            BlockPos ahead = feet.offset(digDirection);
            BlockPos step = ahead.down();
            List<BlockPos> clear = List.of(ahead.up(), ahead, step);
            // Water or lava under the step: that way down ends in it, so turn before digging anything there.
            boolean refused = !world.getFluidState(step.down()).isEmpty();
            for (BlockPos cell : clear) {
                if (refused) break;
                if (passable(world, cell)) continue;
                if (blocked(cell) || liquidNear(world, cell)) { refused = true; break; }
                if (breakableNow(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString()));
                refused = true;
                break;
            }
            if (!refused) {
                // Never step into a drop deeper than the navigator allows; turn away from a pit instead.
                BlockPos landing = step;
                while (passable(world, landing.down()) && landing.getY() > feet.getY() - NativeNavigator.MAX_SAFE_DROP) landing = landing.down();
                if (!passable(world, landing.down()) && !liquidNear(world, landing) && !blocked(landing)) {
                    return Optional.of(go(landing, 0.4, false, label + ": step down"));
                }
            }
            digDirection = digDirection.rotateYClockwise();
        }
        return Optional.empty();
    }

    /**
     * A staircase one step up along {@code digDirection}: clear the head room above, then the two cells over the
     * block ahead, and step up onto it. The way out when the job needs the surface.
     */
    private Optional<Task> climb(NativePlayerBody body, String label) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (int turn = 0; turn < 4; turn++) {
            BlockPos step = feet.offset(digDirection);
            BlockPos landing = step.up();
            boolean refused = passable(world, step); // Nothing to climb onto this way.
            for (BlockPos cell : List.of(feet.up(2), landing, landing.up())) {
                if (refused || passable(world, cell)) continue;
                if (blocked(cell) || liquidNear(world, cell)) { refused = true; break; }
                if (breakableNow(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString()));
                refused = true;
            }
            if (!refused) return Optional.of(go(landing, 0.4, false, label + ": step up"));
            digDirection = digDirection.rotateYClockwise();
        }
        return Optional.empty();
    }

    /** A two-high tunnel forward along {@code digDirection}; walls it exposes are scanned for ores. */
    private Optional<Task> tunnel(NativePlayerBody body, String label) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (int turn = 0; turn < 4; turn++) {
            BlockPos ahead = feet.offset(digDirection);
            boolean refused = false;
            for (BlockPos cell : List.of(ahead.up(), ahead)) {
                if (passable(world, cell)) continue;
                if (blocked(cell) || liquidNear(world, cell)) { refused = true; break; }
                if (breakableNow(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString()));
                refused = true;
                break;
            }
            // Walking dug ground again is fine on the way to rock it can dig; pacing a dead-end corridor is not.
            if (!refused && !passable(world, ahead.down()) && !liquidNear(world, ahead)
                    && (!tunnelled.contains(ahead.asLong()) || diggableFaceAhead(body, ahead))) {
                return Optional.of(go(ahead, 0.4, false, label + ": advance"));
            }
            if (!refused && passable(world, ahead.down()) && !passable(world, ahead.down(2))) {
                return Optional.of(go(ahead.down(), 0.4, false, label + ": step down"));
            }
            digDirection = digDirection.rotateYClockwise();
        }
        return Optional.empty();
    }

    /**
     * Along {@code digDirection} from {@code start}, over open floored ground for up to eight blocks, the first rock
     * face is one this player could dig: not set aside, no liquid at it, and a block its tools can harvest.
     */
    private boolean diggableFaceAhead(NativePlayerBody body, BlockPos start) {
        var world = body.entity().getServerWorld();
        BlockPos at = start;
        for (int k = 0; k < 8; k++) {
            BlockPos next = at.offset(digDirection);
            boolean open = passable(world, next) && passable(world, next.up());
            if (!open) {
                for (BlockPos face : List.of(next, next.up())) {
                    if (passable(world, face)) continue;
                    if (blocked(face) || liquidNear(world, face) || !harvestable(body, face)) return false;
                }
                return true;
            }
            if (passable(world, next.down()) || liquidNear(world, next)) return false; // A drop or water on the way.
            at = next;
        }
        return false;
    }

    private static boolean passable(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty() && !state.getFluidState().isIn(FluidTags.LAVA);
    }

    /** Lava on any side, or water above or beside, which would pour into the opening. */
    private static boolean liquidNear(ServerWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            var fluid = world.getFluidState(pos.offset(direction));
            if (fluid.isIn(FluidTags.LAVA) || direction != Direction.DOWN && fluid.isIn(FluidTags.WATER)) return true;
        }
        return world.getFluidState(pos).isIn(FluidTags.LAVA);
    }

    // ------------------------------------------------------------------ crafting and smelting

    private Optional<Task> craft(NativePlayerBody body, String item) {
        String recipe = item.equals("planks") ? planksRecipe(body) : item;
        if (recipe == null) return Optional.empty();
        if (body.planCraft2x2(recipe).isPresent()) return Optional.of(new Task("native_craft", Map.of("recipe", recipe), "Craft " + recipe.replace('_', ' ')));
        var player = body.entity();
        var table = nearestWorkstation(player, Blocks.CRAFTING_TABLE, 16);
        if (table.isEmpty()) {
            var anyTable = body.findNearbyBlock(Blocks.CRAFTING_TABLE, 4.5);
            if (anyTable.isPresent() && body.planCraft(recipe, anyTable.get()).isPresent()) {
                return Optional.of(new Task("native_craft", Map.of("recipe", recipe), "Craft " + recipe.replace('_', ' ')));
            }
            return Optional.empty();
        }
        if (eyeDistance(player, table.get()) <= 4.4 && body.planCraft(recipe, table.get()).isPresent()) {
            return Optional.of(new Task("native_craft", Map.of("recipe", recipe), "Craft " + recipe.replace('_', ' ')));
        }
        return Optional.of(go(table.get(), 2.5, false, "Walk to the crafting table"));
    }

    private static String planksRecipe(NativePlayerBody body) {
        var inventory = body.entity().getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isIn(ItemTags.LOGS)) continue;
            String path = Registries.ITEM.getId(stack.getItem()).getPath().replace("stripped_", "");
            for (String suffix : List.of("_log", "_wood", "_stem", "_hyphae")) {
                if (path.endsWith(suffix)) {
                    String recipe = path.substring(0, path.length() - suffix.length()) + "_planks";
                    if (body.planCraft2x2(recipe).isPresent()) return recipe;
                }
            }
        }
        return null;
    }

    /** Puts an owned block item on solid ground beside the player, moving it to the hotbar first if needed. */
    private Optional<Task> placeItem(NativePlayerBody body, Item item, String label) {
        var player = body.entity();
        var inventory = player.getInventory();
        int slot = -1;
        for (int s = 0; s < 36; s++) if (inventory.getStack(s).isOf(item)) { slot = s; break; }
        if (slot < 0) return Optional.empty();
        if (slot >= 9) return Optional.of(swapToHotbar(body, slot, "Ready " + Registries.ITEM.getId(item).getPath().replace('_', ' ')));
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            for (BlockPos spot : List.of(feet.offset(direction), feet.offset(direction, 2))) {
                if (!world.getBlockState(spot).isReplaceable() || !world.getFluidState(spot).isEmpty()) continue;
                if (world.getBlockState(spot.down()).getCollisionShape(world, spot.down()).isEmpty()) continue;
                if (eyeDistance(player, spot) > 4.4 || blocked(spot)) continue;
                return Optional.of(new Task("native_place", coords(spot, Map.of("side", "up", "slot", String.valueOf(slot))), label));
            }
        }
        // Make room: carve a pocket into a side wall at foot level, keeping the way along a tunnel or staircase open.
        List<Direction> sides = List.of(digDirection.rotateYClockwise(), digDirection.rotateYCounterclockwise(), digDirection.getOpposite(), digDirection);
        for (Direction direction : sides) {
            BlockPos wall = feet.offset(direction);
            if (passable(world, wall) || liquidNear(world, wall) || blocked(wall)) continue;
            if (world.getBlockState(wall.down()).getCollisionShape(world, wall.down()).isEmpty()) continue;
            if (breakableNow(body, wall)) return Optional.of(clearAt(wall, "Clear space for a workstation"));
        }
        return Optional.empty();
    }

    private Task swapToHotbar(NativePlayerBody body, int slot, String label) {
        var inventory = body.entity().getInventory();
        int target = 8;
        for (int s = 0; s < 9; s++) if (inventory.getStack(s).isEmpty()) { target = s; break; }
        return new Task("native_swap", Map.of("source", String.valueOf(slot), "target", String.valueOf(target)), label);
    }

    private Optional<Task> smelt(NativePlayerBody body) {
        var player = body.entity();
        var furnace = nearestWorkstation(player, Blocks.FURNACE, 16);
        if (furnace.isEmpty()) return placeItem(body, Items.FURNACE, "Place a furnace");
        var inventory = player.getInventory();
        int input = -1, fuel = -1, fuelScore = -1;
        for (int s = 0; s < 36; s++) {
            ItemStack stack = inventory.getStack(s);
            if (stack.isOf(Items.RAW_IRON) && input < 0) input = s;
            if (NativePlayerBody.isSafeAutonomousFuel(stack) && !stack.isOf(Items.RAW_IRON)) {
                int score = NativePlayerBody.fuelPriorityScore(stack);
                if (score > fuelScore) { fuelScore = score; fuel = s; }
            }
        }
        var args = new LinkedHashMap<String, String>();
        args.put("action", "load");
        if (input < 0) {
            // The raw iron is already in the furnace and it ran out of fuel: add fuel only.
            boolean waiting = player.getServerWorld().getBlockEntity(furnace.get()) instanceof AbstractFurnaceBlockEntity block
                    && block.getStack(0).isOf(Items.RAW_IRON) && block.getStack(1).isEmpty();
            if (!waiting || fuel < 0) return Optional.empty();
            args.put("fuel_slot", String.valueOf(fuel));
            return atWorkstation(player, furnace.get(), new Task("native_smelt", coords(furnace.get(), args), "Add fuel to the furnace"));
        }
        args.put("input_slot", String.valueOf(input));
        if (fuel >= 0) args.put("fuel_slot", String.valueOf(fuel));
        return atWorkstation(player, furnace.get(), new Task("native_smelt", coords(furnace.get(), args), "Load raw iron and fuel"));
    }

    // ------------------------------------------------------------------ water, obsidian, flint

    private Optional<Task> fillWater(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var source = knowledge.nearest(world, WorldKnowledge.Kind.WATER, feet, p -> !blocked(p) && p.getSquaredDistance(feet) < 64 * 64);
        if (source.isEmpty()) return Optional.empty();
        BlockPos water = source.get();
        if (eyeDistance(player, water) <= 4.0) {
            return Optional.of(new Task("native_bucket", coords(water, Map.of("mode", "fill")), "Fill a bucket at " + water.toShortString()));
        }
        return Optional.of(go(water, 2.5, false, "Walk to water at " + water.toShortString()));
    }

    private Optional<Task> obsidian(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (obsidianSite != null && !siteStillUseful(world, obsidianSite)) obsidianSite = null;
        if (obsidianSite == null) {
            obsidianSite = findObsidianSite(player).orElse(null);
            pourWaits = 0;
        }
        if (obsidianSite == null) return Optional.empty();
        ObsidianSite site = obsidianSite;
        BlockPos feet = player.getBlockPos();
        boolean hardened = world.getBlockState(site.lava()).isOf(Blocks.OBSIDIAN);
        // Flowing water nudges a player on the far shore; from the poured cell the obsidian is close and in plain view.
        if (hardened && !feet.equals(site.water())) return Optional.of(go(site.water(), 0.3, false, "Obsidian: step onto the shore"));
        if (!hardened && !feet.equals(site.stand())) {
            return Optional.of(go(site.stand(), 0.3, false, "Obsidian: stand beside lava at " + site.lava().toShortString()));
        }
        boolean lavaStill = world.getFluidState(site.lava()).isIn(FluidTags.LAVA);
        boolean waterPlaced = world.getFluidState(site.water()).isIn(FluidTags.WATER);
        if (lavaStill && !waterPlaced) {
            return Optional.of(new Task("native_bucket", coords(site.water(), Map.of("mode", "pour")), "Obsidian: pour water beside lava"));
        }
        // Poured water takes a few ticks to run over the lava; give it a moment before giving up on the site.
        if (lavaStill && waterPlaced && pourWaits < 3) {
            pourWaits++;
            return Optional.of(new Task("native_wait", Map.of("seconds", "1"), "Obsidian: let the water reach the lava"));
        }
        if (hardened) {
            return Optional.of(new Task("native_break", coords(site.lava(), Map.of("guarded", "true")), "Obsidian: mine " + site.lava().toShortString()));
        }
        if (waterPlaced && world.getFluidState(site.water()).isStill()) {
            obsidianSite = null; // Done with this lava source; recover the water for the next one.
            return Optional.of(new Task("native_bucket", coords(site.water(), Map.of("mode", "fill")), "Obsidian: recover the water"));
        }
        obsidianSite = null;
        return Optional.empty();
    }

    private static boolean siteStillUseful(ServerWorld world, ObsidianSite site) {
        return world.getFluidState(site.lava()).isIn(FluidTags.LAVA) || world.getBlockState(site.lava()).isOf(Blocks.OBSIDIAN)
                || world.getFluidState(site.water()).isIn(FluidTags.WATER);
    }

    private Optional<ObsidianSite> findObsidianSite(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var navigator = new NativeNavigator(world);
        ObsidianSite best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos lava : knowledge.all(WorldKnowledge.Kind.LAVA)) {
            if (blocked(lava) || lava.getSquaredDistance(feet) > 48 * 48) continue;
            if (!world.getFluidState(lava).isStill() || !world.getFluidState(lava).isIn(FluidTags.LAVA)) continue;
            BlockState below = world.getBlockState(lava.down());
            if (below.getCollisionShape(world, lava.down()).isEmpty()) continue;
            for (Direction d : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                BlockPos water = lava.offset(d);
                BlockPos stand = water.offset(d);
                if (!navigator.clear(water) || world.getBlockState(water.down()).getCollisionShape(world, water.down()).isEmpty()) continue;
                if (!navigator.standable(stand) || lavaAdjacent(world, stand) || lavaAdjacentExcept(world, water, lava)) continue;
                double distance = stand.getSquaredDistance(feet);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new ObsidianSite(lava.toImmutable(), water.toImmutable(), stand.toImmutable());
                }
            }
            // A pool recessed below its shore, the usual cave case: pour onto the shore beside the open cell above
            // the lava, and the water runs out over the pool, hardening every source it covers.
            BlockPos above = lava.up();
            if (!navigator.clear(above) || !world.getFluidState(above).isEmpty()) continue;
            for (Direction d : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                BlockPos water = above.offset(d);
                BlockPos stand = water.offset(d);
                if (!navigator.standable(water) || !world.getFluidState(water.down()).isEmpty()) continue;
                if (!navigator.standable(stand) || lavaAdjacent(world, stand) || lavaAdjacent(world, water)) continue;
                double distance = stand.getSquaredDistance(feet) + 4; // Prefer the one-source method when both exist.
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new ObsidianSite(lava.toImmutable(), water.toImmutable(), stand.toImmutable());
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean lavaAdjacent(ServerWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) if (world.getFluidState(pos.offset(direction)).isIn(FluidTags.LAVA)) return true;
        return world.getFluidState(pos).isIn(FluidTags.LAVA) || world.getFluidState(pos.up()).isIn(FluidTags.LAVA);
    }

    private static boolean lavaAdjacentExcept(ServerWorld world, BlockPos pos, BlockPos allowed) {
        for (Direction direction : Direction.values()) {
            BlockPos near = pos.offset(direction);
            if (!near.equals(allowed) && world.getFluidState(near).isIn(FluidTags.LAVA)) return true;
        }
        return false;
    }

    private Optional<Task> flint(NativePlayerBody body) {
        var player = body.entity();
        var inventory = player.getInventory();
        int gravelSlot = -1;
        for (int s = 0; s < 36; s++) if (inventory.getStack(s).isOf(Items.GRAVEL)) { gravelSlot = s; break; }
        // Gravel carried from earlier digging can be set down and dug again until it drops flint.
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            BlockPos spot = feet.offset(direction);
            if (world.getBlockState(spot).isOf(Blocks.GRAVEL) && breakableNow(body, spot)) return Optional.of(breakAt(spot, "Dig gravel for flint"));
        }
        if (gravelSlot >= 0) return placeItem(body, Items.GRAVEL, "Set down gravel to dig for flint");
        return gather(body, WorldKnowledge.Kind.GRAVEL, 48, "gravel").or(() -> search(body, "gravel"));
    }

    // ------------------------------------------------------------------ portal

    private Optional<Task> buildPortal(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (portalSite == null) portalSite = choosePortalSite(player).orElse(null);
        if (portalSite == null) return tunnel(body, "Portal: look for open ground");
        PortalSite site = portalSite;
        if (!player.getBlockPos().equals(site.stand())) return Optional.of(go(site.stand(), 0.3, false, "Portal: stand in front of the frame site"));
        Optional<Task> clear = clearPortalSite(body, site, "Portal");
        if (clear.isPresent() || portalSite == null) return clear;
        // Place: bottom row and corners on the ground, sides upward, top corners, then the top row against them.
        int[][] order = {{1, 0}, {2, 0}, {0, 0}, {3, 0}, {0, 1}, {3, 1}, {0, 2}, {3, 2}, {0, 3}, {3, 3}, {0, 4}, {3, 4}, {1, 4}, {2, 4}};
        for (int[] cell : order) {
            BlockPos pos = site.at(cell[0], cell[1]);
            boolean corner = (cell[0] == 0 || cell[0] == 3) && (cell[1] == 0 || cell[1] == 4);
            BlockState state = world.getBlockState(pos);
            if (corner ? !state.isReplaceable() : state.isOf(Blocks.OBSIDIAN)) continue;
            Item item = corner ? cornerItem(body) : Items.OBSIDIAN;
            if (item == null) return Optional.empty();
            int slot = hotbarSlot(body, item);
            if (slot < 0) return Optional.of(swapToHotbar(body, slotOf(body, item), "Portal: ready " + Registries.ITEM.getId(item).getPath()));
            Direction side = cell[1] == 4 && !corner ? (cell[0] == 1 ? site.along() : site.along().getOpposite()) : Direction.UP;
            return Optional.of(new Task("native_place", coords(pos, Map.of("side", side.getName(), "slot", String.valueOf(slot))),
                    "Portal: place " + (corner ? "corner" : "obsidian") + " at " + pos.toShortString()));
        }
        return Optional.empty();
    }

    /**
     * Open the room for a frame: the row in front of it first, so every frame cell can be seen and reached, then the
     * frame plane and its interior. A cell that cannot be cleared abandons the site ({@code portalSite} becomes null).
     */
    private Optional<Task> clearPortalSite(NativePlayerBody body, PortalSite site, String label) {
        var world = body.entity().getServerWorld();
        for (int up = 0; up <= 4; up++) {
            for (int across = 0; across <= 3; across++) {
                BlockPos cell = site.front(across, up);
                if (cell.equals(site.stand()) || cell.equals(site.stand().up())) continue;
                if (world.getBlockState(cell).isReplaceable() && world.getFluidState(cell).isEmpty()) continue;
                if (!world.getFluidState(cell).isEmpty()) continue; // Water left from casting drains away by itself.
                Optional<Task> clear = clearOrApproach(body, site, cell, label);
                if (clear.isPresent() || portalSite == null) return clear;
            }
        }
        for (int up = 0; up <= 4; up++) {
            for (int across = 0; across <= 3; across++) {
                BlockPos cell = site.at(across, up);
                BlockState state = world.getBlockState(cell);
                boolean frame = across == 0 || across == 3 || up == 0 || up == 4;
                boolean corner = (across == 0 || across == 3) && (up == 0 || up == 4);
                boolean wanted = frame ? (corner ? !state.isReplaceable() : state.isOf(Blocks.OBSIDIAN)) : state.isAir();
                if (wanted) continue;
                if (!state.isReplaceable() && !state.isOf(Blocks.OBSIDIAN)) {
                    Optional<Task> clear = clearOrApproach(body, site, cell, label);
                    if (clear.isPresent() || portalSite == null) return clear;
                }
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ casting

    /** The lava source the casts are refilled from. */
    private BlockPos castLava;
    /** Where the player stood when a cast was last refused or failed. */
    private BlockPos castFailedFrom;

    /**
     * Cast the frame in place, as speedrunners build a portal without a diamond pickaxe: a bucket of lava from a
     * source it has seen, a site cleared as for a mined frame, cobblestone corners, then the ten frame blocks cast
     * bottom-up, each by one recorded {@code native_cast} (water above, lava in, the water taken back) after a mould
     * block from its own stock behind the cell and the one above, so every pour has a face to aim at. Once the frame
     * is complete the plan moves on to flint and steel and ignition.
     */
    private Optional<Task> castPortal(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        var inventory = player.getInventory();
        BlockPos feet = player.getBlockPos();
        if (castLava != null && !(world.getFluidState(castLava).isIn(FluidTags.LAVA) && world.getFluidState(castLava).isStill())) castLava = null;
        if (castLava == null) castLava = knowledge.nearest(world, WorldKnowledge.Kind.LAVA, feet, p -> !blocked(p)).orElse(null);
        if (ItemMoves.count(inventory, Items.LAVA_BUCKET) == 0) {
            if (castLava == null) {
                return cave(body, "lava", DIAMOND_TARGET).or(this::lookAround).or(() -> descendOrTunnel(body, DIAMOND_TOP, DIAMOND_TARGET, "lava"));
            }
            if (ItemMoves.count(inventory, Items.BUCKET) == 0) return Optional.empty();
            if (eyeDistance(player, castLava) > 4.2) return Optional.of(go(castLava, 2.5, false, "Cast: walk to the lava"));
            if (feet.equals(castFailedFrom)) return Optional.of(go(castLava, 1.5, false, "Cast: walk up to the lava"));
            return Optional.of(new Task("native_bucket", coords(castLava, Map.of("mode", "fill")), "Cast: fill a bucket with lava"));
        }
        if (portalSite == null) {
            portalSite = choosePortalSite(player).orElse(null);
            if (portalSite == null) return tunnel(body, "Cast: look for room for the frame");
        }
        PortalSite site = portalSite;
        if (!player.getBlockPos().equals(site.stand()) && clearPortalSiteNeeded(world, site)) {
            return Optional.of(go(site.stand(), 0.3, false, "Cast: stand in front of the frame site"));
        }
        Optional<Task> clear = clearPortalSite(body, site, "Cast");
        if (clear.isPresent() || portalSite == null) return clear;
        int[][] order = {{0, 0}, {3, 0}, {1, 0}, {2, 0}, {0, 1}, {3, 1}, {0, 2}, {3, 2}, {0, 3}, {3, 3}, {0, 4}, {3, 4}, {1, 4}, {2, 4}};
        for (int[] at : order) {
            BlockPos cell = site.at(at[0], at[1]);
            boolean corner = (at[0] == 0 || at[0] == 3) && (at[1] == 0 || at[1] == 4);
            BlockState state = world.getBlockState(cell);
            if (corner ? !state.isReplaceable() : state.isOf(Blocks.OBSIDIAN)) continue;
            if (corner) return within(body, site, at[0], cell, () -> placeBlock(body, cell, "Cast: corner at " + cell.toShortString()));
            // A cast needs open air at the cell and above it; water left by the last cast drains in a moment.
            for (BlockPos open : List.of(cell, cell.up())) {
                if (world.getBlockState(open).isAir()) continue;
                if (!world.getFluidState(open).isEmpty()) return Optional.of(new Task("native_wait", Map.of("seconds", "1"), "Cast: let the water drain"));
                if (breakableNow(body, open)) return Optional.of(clearAt(open, "Cast: clear " + open.toShortString()));
                portalSite = null;
                return Optional.empty();
            }
            // The mould: a block behind the cell and behind the one above, faces the pours can be aimed at from the front.
            for (BlockPos back : List.of(cell.offset(site.toward()), cell.up().offset(site.toward()))) {
                if (!passable(world, back)) continue;
                Optional<Task> mould = within(body, site, at[0], back, () -> placeBlock(body, back, "Cast: mould at " + back.toShortString()));
                if (mould.isPresent()) return mould;
            }
            if (player.getBlockPos().equals(castFailedFrom)) {
                Optional<Task> move = stepCloser(body, site, at[0], cell);
                if (move.isPresent()) return move;
            }
            return within(body, site, at[0], cell, () -> Optional.of(new Task("native_cast", coords(cell, Map.of()), "Cast obsidian at " + cell.toShortString())));
        }
        return Optional.empty();
    }

    /**
     * Break {@code cell} if it can be broken from here, walk to the site's stand if it is only out of reach, and
     * otherwise give the site up ({@code portalSite} becomes null).
     */
    private Optional<Task> clearOrApproach(NativePlayerBody body, PortalSite site, BlockPos cell, String label) {
        var player = body.entity();
        if (breakableNow(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString()));
        if (!player.getBlockPos().equals(site.stand()) && eyeDistance(player, cell) > REACH) {
            return Optional.of(go(site.stand(), 0.3, false, label + ": stand in front of the frame site"));
        }
        portalSite = null;
        return Optional.empty();
    }

    private boolean clearPortalSiteNeeded(ServerWorld world, PortalSite site) {
        for (int up = 0; up <= 4; up++) for (int across = 0; across <= 3; across++) {
            BlockPos cell = site.front(across, up);
            if (!world.getBlockState(cell).isReplaceable()) return true;
        }
        return false;
    }

    /** Eye to block centre: the mould's top row, two blocks back and five up, is just inside it from the front row. */
    private static final double CAST_REACH = 4.4;

    /** Do {@code then} from where the player stands if {@code cell} is in reach, else step along the front row to a spot that is. */
    private Optional<Task> within(NativePlayerBody body, PortalSite site, int across, BlockPos cell, java.util.function.Supplier<Optional<Task>> then) {
        Optional<Task> now = eyeDistance(body.entity(), cell) <= CAST_REACH + 0.3 ? then.get() : Optional.<Task>empty();
        return now.isPresent() ? now : stepCloser(body, site, across, cell);
    }

    /** A step to another spot on the front row (or the stand) from which {@code cell} is in reach. */
    private Optional<Task> stepCloser(NativePlayerBody body, PortalSite site, int across, BlockPos cell) {
        var player = body.entity();
        var navigator = new NativeNavigator(player.getServerWorld());
        for (BlockPos stand : List.of(site.front(Math.clamp(across, 0, 3), 0), site.front(1, 0), site.front(2, 0), site.front(0, 0), site.front(3, 0), site.stand())) {
            if (stand.equals(player.getBlockPos()) || !navigator.standable(stand) || blocked(stand)) continue;
            if (Vec3d.ofBottomCenter(stand).add(0, 1.62, 0).distanceTo(Vec3d.ofCenter(cell)) > CAST_REACH) continue;
            return Optional.of(go(stand, 0.3, false, "Cast: step closer"));
        }
        return Optional.empty();
    }

    private static Item cornerItem(NativePlayerBody body) {
        var inventory = body.entity().getInventory();
        for (Item item : List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DIRT, Items.NETHERRACK)) {
            for (int s = 0; s < 36; s++) if (inventory.getStack(s).isOf(item)) return item;
        }
        return null;
    }

    private static int hotbarSlot(NativePlayerBody body, Item item) {
        var inventory = body.entity().getInventory();
        for (int s = 0; s < 9; s++) if (inventory.getStack(s).isOf(item)) return s;
        return -1;
    }

    private static int slotOf(NativePlayerBody body, Item item) {
        var inventory = body.entity().getInventory();
        for (int s = 0; s < 36; s++) if (inventory.getStack(s).isOf(item)) return s;
        return -1;
    }

    /** Where a site search last found no room, so it is not repeated every tick from the same spot. */
    private BlockPos noSiteFrom;

    /**
     * A frame site in front of where the player stands, or else in front of the nearest open spot within a few
     * blocks that has room, as a player steps aside to find a clear patch of ground.
     */
    private Optional<PortalSite> choosePortalSite(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        var navigator = new NativeNavigator(world);
        BlockPos feet = player.getBlockPos();
        if (feet.equals(noSiteFrom)) return Optional.empty();
        for (int r = 0; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                for (int dy : r == 0 ? new int[]{0} : new int[]{0, 1, -1}) {
                    BlockPos stand = feet.add(dx, dy, dz);
                    if (r > 0 && blocked(stand)) continue;
                    Optional<PortalSite> site = portalSiteFrom(world, navigator, stand);
                    if (site.isPresent()) return site;
                }
            }
        }
        noSiteFrom = feet;
        return Optional.empty();
    }

    private static Optional<PortalSite> portalSiteFrom(ServerWorld world, NativeNavigator navigator, BlockPos stand) {
        Vec3d eye = Vec3d.ofBottomCenter(stand).add(0, 1.62, 0);
        for (int distance = 2; distance <= 2; distance++) {
            for (Direction facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                Direction along = facing.rotateYClockwise();
                BlockPos origin = stand.offset(facing, distance).offset(along.getOpposite(), 1);
                boolean ok = navigator.standable(stand);
                for (int across = 0; across <= 3 && ok; across++) {
                    BlockPos ground = origin.offset(along, across).down();
                    if (world.getBlockState(ground).getCollisionShape(world, ground).isEmpty()) ok = false;
                    for (int up = 0; up <= 4 && ok; up++) {
                        BlockPos cell = origin.offset(along, across).up(up);
                        var state = world.getBlockState(cell);
                        if (!world.getFluidState(cell).isEmpty() || state.hasBlockEntity() || state.getHardness(world, cell) < 0) ok = false;
                        else if (eye.distanceTo(Vec3d.ofCenter(cell)) > 4.4) ok = false;
                    }
                }
                for (int across = 0; across <= 3 && ok; across++) {
                    for (int up = 0; up <= 4 && ok; up++) {
                        BlockPos cell = origin.offset(along, across).up(up).offset(facing.getOpposite());
                        if (!world.getFluidState(cell).isEmpty() || world.getBlockState(cell).hasBlockEntity()) ok = false;
                    }
                }
                if (ok) return Optional.of(new PortalSite(origin.toImmutable(), along, facing, stand.toImmutable()));
            }
        }
        return Optional.empty();
    }

    private static int frameObsidian(ServerWorld world, PortalSite site) {
        int placed = 0;
        for (int up = 0; up <= 4; up++) {
            for (int across = 0; across <= 3; across++) {
                boolean frame = across == 0 || across == 3 || up == 0 || up == 4;
                boolean corner = (across == 0 || across == 3) && (up == 0 || up == 4);
                if (frame && !corner && world.getBlockState(site.at(across, up)).isOf(Blocks.OBSIDIAN)) placed++;
            }
        }
        return placed;
    }

    private static int frameCorners(ServerWorld world, PortalSite site) {
        int placed = 0;
        for (int[] corner : new int[][]{{0, 0}, {3, 0}, {0, 4}, {3, 4}}) {
            if (!world.getBlockState(site.at(corner[0], corner[1])).isReplaceable()) placed++;
        }
        return placed;
    }

    private static boolean portalComplete(ServerWorld world, PortalSite site) {
        for (int up = 0; up <= 4; up++) {
            for (int across = 0; across <= 3; across++) {
                boolean frame = across == 0 || across == 3 || up == 0 || up == 4;
                boolean corner = (across == 0 || across == 3) && (up == 0 || up == 4);
                if (frame && !corner && !world.getBlockState(site.at(across, up)).isOf(Blocks.OBSIDIAN)) return false;
            }
        }
        return true;
    }

    private Optional<Task> ignite(NativePlayerBody body) {
        if (portalSite == null) return Optional.empty();
        int slot = hotbarSlot(body, Items.FLINT_AND_STEEL);
        if (slot < 0) {
            int anywhere = slotOf(body, Items.FLINT_AND_STEEL);
            return anywhere < 0 ? Optional.empty() : Optional.of(swapToHotbar(body, anywhere, "Portal: ready flint and steel"));
        }
        BlockPos base = portalSite.at(1, 0);
        return Optional.of(new Task("native_ignite_portal", coords(base, Map.of("slot", String.valueOf(slot))), "Portal: light it"));
    }

    // ------------------------------------------------------------------ defense

    /**
     * A hostile mob that is after this player and in sight is dealt with before work continues: close in and
     * strike, as a player with a weapon would rather than keep mining while a skeleton shoots. Creepers are left
     * to the runtime's retreat, and a badly hurt player leaves it to the tactical retreat too.
     */
    private Optional<Task> defend(NativePlayerBody body) {
        var player = body.entity();
        // A shooter with a clear line of fire that cannot be fought now (too hurt, or out of reach last time): get
        // out of its line of fire behind an owned block, as a player ducks behind cover.
        HostileEntity shooter = shooter(player);
        if (shooter != null && (player.getHealth() < 6 || ignoredFoes.getOrDefault(shooter.getUuid(), Long.MIN_VALUE) >= lastTick)) {
            Optional<Task> cover = cover(body, shooter);
            if (cover.isPresent()) return cover;
        }
        // A creeper comes first even when badly hurt: a general back-off averages away from every mob and can walk
        // past it, and only the creeper's own escape outranks the runtime's one-block retreat.
        CreeperEntity creeper = creeperAfter(player);
        if (creeper != null) {
            // Armed and healthy: hit and run, which keeps it from getting close enough to light. Otherwise get away.
            foe = creeper.getUuid(); // A fight that fails leaves it alone for a while, as with any foe.
            foeUntil = lastTick;
            Task strike = strike(player, creeper, "Fight creeper");
            // A shield in the off hand stops the blast, so a shielded player takes a creeper on with less health to spare.
            float nerve = player.getOffHandStack().isOf(Items.SHIELD) ? 6 : 10;
            if (strike.args().containsKey("slot") && player.getHealth() >= nerve) return Optional.of(new Task("native_fight", strike.args(), strike.label()));
            if (lastTick >= runFailedUntil) return Optional.of(runFrom(player, creeper));
            // Nowhere to run (a pit, a dead end): wall it off, and failing that, knock it back with whatever is in hand.
            Optional<Task> wall = cover(body, creeper);
            return wall.isPresent() ? wall : Optional.of(new Task("native_fight", strike.args(), strike.label()));
        }
        if (player.getHealth() < 6) return backOff(player);
        // Outnumbered and already hurt: do not take them on one by one in the dark; get some distance first.
        if (player.getHealth() < 14 && hunters(player) >= 3) return backOff(player);
        var threats = threats(player);
        HostileEntity nearest = threats.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
        if (nearest == null) nearest = rememberedFoe(player);
        if (nearest == null) return Optional.empty();
        // A skeleton that steps behind cover is still out there: keep after it for a few seconds.
        foe = nearest.getUuid();
        foeUntil = lastTick + 100;
        String name = Registries.ENTITY_TYPE.getId(nearest.getType()).getPath();
        Task strike = strike(player, nearest, "Fight " + name);
        return Optional.of(new Task("native_fight", strike.args(), strike.label()));
    }

    private static int hunters(ServerPlayerEntity player) {
        return player.getServerWorld().getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(12, 6, 12),
                mob -> mob.isAlive() && mob.getTarget() == player).size();
    }

    /** Too hurt to fight: put distance between the player and whatever is after it. */
    private Optional<Task> backOff(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        var hunters = world.getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(16, 6, 16),
                mob -> mob.isAlive() && mob.getTarget() == player);
        if (hunters.isEmpty()) return Optional.empty();
        Vec3d away = Vec3d.ZERO;
        for (HostileEntity mob : hunters) away = away.add(player.getPos().subtract(mob.getPos()).normalize());
        if (away.lengthSquared() < 1.0E-4) return Optional.empty();
        away = away.normalize().multiply(10);
        BlockPos target = BlockPos.ofFloored(player.getX() + away.x, player.getY(), player.getZ() + away.z);
        return Optional.of(go(target, 3, true, "Back off from " + Registries.ENTITY_TYPE.getId(hunters.getFirst().getType()).getPath()));
    }

    /**
     * A creeper close by that is after the player or already hissing. One that could not be fought is left alone
     * for a while unless it comes within five blocks.
     */
    private CreeperEntity creeperAfter(ServerPlayerEntity player) {
        return player.getServerWorld().getEntitiesByClass(CreeperEntity.class, player.getBoundingBox().expand(8, 4, 8),
                        creeper -> creeper.isAlive() && (creeper.getTarget() == player || creeper.getFuseSpeed() > 0) && player.canSee(creeper)
                                && (ignoredFoes.getOrDefault(creeper.getUuid(), Long.MIN_VALUE) < lastTick || player.squaredDistanceTo(creeper) < 5 * 5))
                .stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    /** Sprint a dozen blocks straight away from a creeper; beyond seven its fuse goes out. */
    private static Task runFrom(ServerPlayerEntity player, CreeperEntity creeper) {
        Vec3d away = player.getPos().subtract(creeper.getPos()).multiply(1, 0, 1);
        if (away.lengthSquared() < 1.0E-4) away = player.getRotationVector().multiply(-1, 0, -1);
        away = away.normalize().multiply(12);
        BlockPos target = BlockPos.ofFloored(player.getX() + away.x, player.getY(), player.getZ() + away.z);
        return new Task("native_goto", coords(target, Map.of("range", "3", "partial", "true", "sprint", "true")), "Run from creeper");
    }

    /** The nearest skeleton-like shooter after the player with a clear line of fire to it. */
    private static HostileEntity shooter(ServerPlayerEntity player) {
        return player.getServerWorld().getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(20, 10, 20),
                        mob -> mob.isAlive() && mob.getTarget() == player && mob instanceof net.minecraft.entity.ai.RangedAttackMob && player.canSee(mob))
                .stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    /** Block the first open cell beside the player's head or feet along the line from {@code shooter} to it. */
    private Optional<Task> cover(NativePlayerBody body, net.minecraft.entity.LivingEntity shooter) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        Vec3d eye = player.getEyePos();
        Vec3d toward = shooter.getEyePos().subtract(eye).normalize();
        for (double d = 0.5; d <= 1.75; d += 0.25) {
            BlockPos cell = BlockPos.ofFloored(eye.add(toward.multiply(d)));
            if (cell.equals(feet) || cell.equals(feet.up())) continue;
            if (!opening(world, cell)) return Optional.empty(); // Something already stands in the way.
            String label = "Take cover from " + Registries.ENTITY_TYPE.getId(shooter.getType()).getPath();
            Optional<Task> place = placeBlock(body, cell, label);
            // Nothing to set it against in the open: build up from the block below it first.
            if (place.isEmpty() && !cell.down().equals(feet) && opening(world, cell.down())) place = placeBlock(body, cell.down(), label);
            return place;
        }
        return Optional.empty();
    }

    /** One owned stone or dirt block into {@code cell}, against any solid face that is not the player's own space. */
    private Optional<Task> placeBlock(NativePlayerBody body, BlockPos cell, String label) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        if (blocked(cell) || eyeDistance(player, cell) > 5.0) return Optional.empty();
        var inventory = player.getInventory();
        int slot = -1;
        for (int s = 0; s < 36 && slot < 0; s++) if (SEALING.contains(inventory.getStack(s).getItem())) slot = s;
        if (slot < 0) return Optional.empty();
        if (slot >= 9) return Optional.of(swapToHotbar(body, slot, "Ready a block"));
        for (Direction side : Direction.values()) {
            BlockPos support = cell.offset(side.getOpposite());
            if (support.equals(feet) || support.equals(feet.up())) continue;
            if (world.getBlockState(support).getCollisionShape(world, support).isEmpty()) continue; // Nothing to place against.
            // Reach is to the face clicked, as for a player: the top of the block below can be in reach when the cell's centre is not.
            Vec3d face = Vec3d.ofCenter(support).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
            if (player.getEyePos().distanceTo(face) > 4.5) continue;
            return Optional.of(new Task("native_place", coords(cell, Map.of("side", side.asString(), "slot", String.valueOf(slot))), label));
        }
        return Optional.empty();
    }

    /**
     * Fill the few openings around where the player waits out the night with an owned stone or dirt block, so
     * nothing walks or shoots in. A spot with more openings than a staircase or tunnel leaves is a cave and is left.
     */
    private Optional<Task> seal(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var open = new java.util.ArrayList<BlockPos>();
        for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            for (BlockPos cell : List.of(feet.offset(direction), feet.up().offset(direction))) if (opening(world, cell)) open.add(cell);
        }
        if (opening(world, feet.up(2))) open.add(feet.up(2));
        if (open.isEmpty() || open.size() > 4) return Optional.empty();
        for (BlockPos cell : open) {
            Optional<Task> place = placeBlock(body, cell, "Seal the shelter at " + cell.toShortString());
            if (place.isPresent()) return place;
        }
        return Optional.empty();
    }

    private static boolean opening(ServerWorld world, BlockPos cell) {
        return passable(world, cell) && world.getFluidState(cell).isEmpty() && world.getBlockState(cell).isReplaceable();
    }

    private static final Set<Item> SEALING = Set.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE,
            Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.COARSE_DIRT, Items.NETHERRACK);

    private HostileEntity rememberedFoe(ServerPlayerEntity player) {
        if (foe == null || lastTick > foeUntil || player.getHealth() < 6 || ignoredFoes.getOrDefault(foe, Long.MIN_VALUE) >= lastTick) return null;
        if (player.getServerWorld().getEntity(foe) instanceof HostileEntity mob && mob.isAlive() && mob.getTarget() == player
                && !(mob instanceof EndermanEntity) && !(mob instanceof CreeperEntity)
                && player.squaredDistanceTo(mob) < 24 * 24) {
            return mob;
        }
        foe = null;
        return null;
    }

    private List<HostileEntity> threats(ServerPlayerEntity player) {
        if (player.getHealth() < 6) return List.of();
        return player.getServerWorld().getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(16, 6, 16),
                mob -> mob.isAlive() && mob.getTarget() == player && !(mob instanceof CreeperEntity) && player.canSee(mob)
                        // An enderman is not chased (it out-teleports and out-hits early gear); it is struck when it comes close.
                        && !(mob instanceof EndermanEntity && player.squaredDistanceTo(mob) > 3.5 * 3.5)
                        && (ignoredFoes.getOrDefault(mob.getUuid(), Long.MIN_VALUE) < lastTick || player.squaredDistanceTo(mob) < 4 * 4));
    }

    /** Why the running goal task should stop, or null: a hostile after the player while other work runs, or a fight that has worn the player down. */
    String stopFor(ServerPlayerEntity player, Task running) {
        if (running != null && player.isInsideWall() && !running.label().startsWith("Dig out of")) return "Stuck in a wall; dig out first";
        if (running == null || running.label().startsWith("Back off") || running.label().startsWith("Run from")
                || running.label().startsWith("Take cover")) return null;
        if (running.label().startsWith("Fight") || running.label().startsWith("Close in")) {
            // Worn down mid-fight: break it off, as a player does, so backing off or taking cover can take over.
            // The same line below which the goal never starts a fight.
            return player.getHealth() < 6 ? "Badly hurt; break off the fight" : null;
        }
        return !threats(player).isEmpty() || player.getHealth() >= 6 && creeperAfter(player) != null
                ? "A hostile mob is attacking; fight back first" : null;
    }

    /** Goal work that outranks everything else: a creeper's escape, and digging out of a block the head is stuck in. */
    static boolean urgentTask(Task task) {
        return creeperTask(task) || task != null && task.label().startsWith("Dig out of");
    }

    /** Creeper work outranks the runtime's one-block retreat: its own hit and run or sprint away is the better escape. */
    static boolean creeperTask(Task task) {
        return task != null && (task.label().equals("Fight creeper") || task.label().equals("Run from creeper")
                || task.label().equals("Take cover from creeper"));
    }

    /**
     * A task that did nothing because things moved under it (the player still finishing a walk, say) is not an
     * unreachable target: try it again, but set it aside after three such refusals in a row.
     */
    void taskRefusedForNow(Task task, long tick) {
        if (repeatFailures.merge(task, 1, Integer::sum) >= 3) {
            repeatFailures.remove(task);
            setAside.put(task, tick + 1200);
        }
    }

    /** Work stopped for a fight is not a failed target; plan again without counting it. */
    void taskInterrupted(Task task) {
        progress = "Interrupted: " + (task == null ? "work" : task.label());
    }

    private static Task strike(ServerPlayerEntity player, net.minecraft.entity.LivingEntity target, String label) {
        int weapon = -1;
        float best = 0;
        for (int s = 0; s < 9; s++) {
            var stack = player.getInventory().getStack(s);
            if (!stack.isIn(ItemTags.SWORDS) && !stack.isIn(ItemTags.AXES)) continue;
            float score = tier(stack.getItem()) + (stack.isIn(ItemTags.SWORDS) ? 0.5f : 0);
            if (score > best) { best = score; weapon = s; }
        }
        var args = new LinkedHashMap<String, String>();
        args.put("target", target.getUuid().toString());
        if (weapon >= 0) args.put("slot", String.valueOf(weapon));
        return new Task("native_attack", Map.copyOf(args), label);
    }

    /** Route items that a break left lying, say carried off by water, are worth walking a few blocks for. */
    private static final Set<Item> KEEP = Set.of(Items.OBSIDIAN, Items.DIAMOND, Items.RAW_IRON, Items.FLINT);

    private Optional<Task> pickUpLoose(ServerPlayerEntity player) {
        // A drop in flowing water, such as the water poured for obsidian, waits until the water is taken back.
        var world = player.getServerWorld();
        var drops = world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(8, 3, 8),
                drop -> KEEP.contains(drop.getStack().getItem()) && player.canSee(drop) && !blocked(drop.getBlockPos())
                        && (world.getFluidState(drop.getBlockPos()).isEmpty() || world.getFluidState(drop.getBlockPos()).isStill()));
        if (drops.isEmpty()) return Optional.empty();
        ItemEntity nearest = drops.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElseThrow();
        return Optional.of(go(nearest.getBlockPos(), 0.6, false, "Pick up " + Registries.ITEM.getId(nearest.getStack().getItem()).getPath().replace('_', ' ')));
    }

    // ------------------------------------------------------------------ food

    private Optional<Task> hunt(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        Box area = player.getBoundingBox().expand(24, 8, 24);
        var drops = world.getEntitiesByClass(ItemEntity.class, area.contract(16, 4, 16),
                drop -> drop.getStack().contains(DataComponentTypes.FOOD) && safeFood(drop.getStack()) && player.canSee(drop)
                        && !blocked(drop.getBlockPos()));
        if (!drops.isEmpty()) {
            ItemEntity drop = drops.getFirst();
            return Optional.of(go(drop.getBlockPos(), 0.6, false, "Pick up food"));
        }
        var animals = world.getEntitiesByClass(PassiveEntity.class, area, animal -> animal.isAlive() && !animal.isBaby() && !blocked(animal.getBlockPos())
                && (animal instanceof CowEntity || animal instanceof PigEntity || animal instanceof SheepEntity
                || animal instanceof ChickenEntity || animal instanceof RabbitEntity) && player.canSee(animal));
        if (animals.isEmpty()) return Optional.empty();
        PassiveEntity nearest = animals.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElseThrow();
        if (player.squaredDistanceTo(nearest) <= 2.8 * 2.8) {
            return Optional.of(strike(player, nearest, "Hunt " + Registries.ENTITY_TYPE.getId(nearest.getType()).getPath()));
        }
        return Optional.of(go(nearest.getBlockPos(), 2.0, false, "Approach " + Registries.ENTITY_TYPE.getId(nearest.getType()).getPath()));
    }
}
