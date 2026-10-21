package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.survival.PlanPreferences;
import dev.kindred.core.survival.PortalRunPlanner;
import dev.kindred.core.survival.PortalRunSnapshot;
import dev.kindred.core.survival.StrategyAdvice;
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
    /** Standing where a cut trunk stood, a player reaches the log this many blocks over its feet (eye 1.62, reach 4.3). */
    private static final int TRUNK_REACH = 5;
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
    /** Cells this player cleared for its own staircases and tunnels. */
    private final Set<Long> dug = new HashSet<>();
    /** 4×4×4 cells of cave the player has already walked through; exploration heads for the rest. */
    private final Set<Long> visitedCaves = new HashSet<>();
    /** Where tunnelling has already advanced: walking an old corridor back and forth is not progress. */
    private final Set<Long> tunnelled = new HashSet<>();
    /** Cells the player has stood in, oldest first, this life: the way back out it knows is walkable. */
    private final java.util.ArrayDeque<BlockPos> trail = new java.util.ArrayDeque<>();
    private static final int TRAIL_LENGTH = 4096;
    /** A run from a creeper found no way out: until then, wall it off or fight it instead. */
    private long runFailedUntil;
    private long backOffFailedUntil;
    private int branchRun;
    private boolean branchSide, branchStepping;
    /** Knowledge sweeps to wait for before digging: the first look at goal start, then one after each cave stride. */
    private int lookUntilSweep = 1;
    private long dropSiteUntil;
    private String progress = "Starting";
    private PortalRunPlanner.Stage stage = PortalRunPlanner.Stage.WOOD;
    private long lastTick;
    // A walk back along its trail that ended where it began (a step up it cannot walk): retracing waits a while.
    private BlockPos retraceFrom;
    private boolean retraceDone;
    private long retraceStalledUntil;
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

    // ------------------------------------------------------------------ strategist (F-19b)

    /** A death as the strategist and the player's memory see it. */
    record Death(BlockPos pos, String cause, PortalRunPlanner.Stage stage) { }

    /** How long a strategist's stance holds without being renewed: five minutes of game time. */
    static final long ADVICE_TICKS = 6_000;
    private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Death> deathsSeen = new java.util.ArrayDeque<>();
    private int deathCount, stuckSpells, eventCount;
    private Boolean reportedNight;
    private float reportedHealth = 20;
    private java.util.UUID reportedFoe;
    private PortalRunPlanner.Stage loggedStage;
    private StrategyAdvice advice;
    private long adviceUntil;
    /** Habits this player keeps in this world (F-19c), from its notes. */
    private final java.util.EnumSet<PlanPreferences.Habit> habits = java.util.EnumSet.noneOf(PlanPreferences.Habit.class);
    /** Game time from which a player that learned to shelter early digs in: the last of the afternoon. */
    static final long DUSK = 11_500;

    /** Something worth telling the strategist, in game seconds; the last sixteen are kept. */
    private void event(String text) {
        event(text, true);
    }

    /** As {@link #event(String)}; {@code news} false for what the strategist need not be asked about again. */
    private void event(String text, boolean news) {
        if (events.size() >= 16) events.removeFirst();
        events.addLast("at " + lastTick / 20 + "s: " + text);
        if (news) eventCount++;
    }

    /** How many events the player has reported: a strategist that follows play asks again when this moves on. */
    int eventCount() { return eventCount; }

    /**
     * Report what is happening as it happens, for a strategist that follows play: a fight with a new foe, a heavy hit
     * since the last report, nightfall and daybreak.
     */
    private void noteHappenings(ServerPlayerEntity player) {
        boolean dark = player.getServerWorld().isNight() && player.getServerWorld().getDimension().hasSkyLight();
        if (reportedNight != null && dark != reportedNight) event(dark ? "night fell" : "day broke");
        reportedNight = dark;
        float health = player.getHealth();
        if (health <= reportedHealth - 6) {
            var source = player.getRecentDamageSource();
            String by = source == null ? "" : source.getAttacker() != null
                    ? " by a " + Registries.ENTITY_TYPE.getId(source.getAttacker().getType()).getPath() : " by " + source.getName();
            event("hurt to " + Math.round(health) + "/20" + by);
            reportedHealth = health;
        } else if (health > reportedHealth) {
            reportedHealth = health; // Healed: the next heavy hit counts from here.
        }
    }

    int deaths() { return deathCount; }
    int stuckSpells() { return stuckSpells; }
    Optional<Death> lastDeath() { return Optional.ofNullable(deathsSeen.peekLast()); }

    /** The stance in force: the strategist's while it holds, else pressing on. */
    StrategyAdvice.Stance stance() {
        return advice != null && lastTick < adviceUntil ? advice.stance() : StrategyAdvice.Stance.PRESS_ON;
    }

    /**
     * Take a strategist's advice: a stance and a focus for a while, the places it names marked as danger for a while
     * too, and a habit its note teaches kept from now on (or one it retires dropped).
     */
    void advise(StrategyAdvice advice, long tick) {
        advise(advice, tick, ADVICE_TICKS);
    }

    /** As {@link #advise(StrategyAdvice, long)}, holding for {@code holdTicks} of game time. */
    void advise(StrategyAdvice advice, long tick, long holdTicks) {
        this.advice = advice;
        adviceUntil = tick + holdTicks;
        for (StrategyAdvice.Place place : advice.avoid()) markDanger(new BlockPos(place.x(), place.y(), place.z()), tick + DANGER_TICKS / 4);
        if (advice.habit() != PlanPreferences.Habit.NONE) habits.add(advice.habit());
        habits.remove(advice.retire());
        // The strategist's own answer is not news to it.
        event("strategist advised " + advice.stance() + (advice.focus() == PlanPreferences.Focus.NONE ? "" : ", " + advice.focus() + " first")
                + (advice.reason().isEmpty() ? "" : ": " + advice.reason()), false);
    }

    /** The habits kept in this world, as the player's memory holds them. */
    void habits(java.util.Set<PlanPreferences.Habit> kept) {
        habits.clear();
        habits.addAll(kept);
        habits.remove(PlanPreferences.Habit.NONE);
    }

    /** The focus in force: the strategist's while its advice holds. */
    PlanPreferences.Focus focus() {
        return advice != null && lastTick < adviceUntil ? advice.focus() : PlanPreferences.Focus.NONE;
    }

    /** What the planner keeps to beyond the route: the habits, and the focus in force. */
    PlanPreferences preferences() {
        return new PlanPreferences(habits, focus());
    }

    /** Places the player itself has reported: where it died, and the danger zones it marked. */
    List<StrategyAdvice.Place> reportedPlaces() {
        var places = new java.util.ArrayList<StrategyAdvice.Place>();
        for (Death death : deathsSeen) places.add(new StrategyAdvice.Place(death.pos().getX(), death.pos().getY(), death.pos().getZ()));
        for (DangerZone zone : dangerZones) places.add(new StrategyAdvice.Place(zone.center().getX(), zone.center().getY(), zone.center().getZ()));
        return places;
    }

    /** What the strategist is told: where the player is, what it has, where the route stands, and what happened. */
    String strategyReport(ServerPlayerEntity player, String name, List<String> memory) {
        var world = player.getServerWorld();
        var s = lastSnapshot;
        var out = new StringBuilder();
        long time = world.getTimeOfDay() % 24_000;
        out.append("Player ").append(name).append(", ").append(world.getRegistryKey().getValue().getPath())
                .append(", day ").append(world.getTimeOfDay() / 24_000 + 1).append(world.isNight() ? ", night" : ", daytime")
                .append(" (time ").append(time).append(").\n");
        BlockPos at = player.getBlockPos();
        out.append("Position ").append(at.getX()).append(' ').append(at.getY()).append(' ').append(at.getZ())
                .append(underground(player) ? ", below ground" : ", on the surface")
                .append(groundBelow(player) ? "" : ", on an island over the void").append(".\n");
        out.append("Health ").append(Math.round(player.getHealth())).append("/20, food ").append(player.getHungerManager().getFoodLevel()).append("/20");
        if (s != null) out.append(", food items carried ").append(s.edibleFood());
        out.append(".\n");
        out.append("Stage: ").append(stageLabel(stage)).append(". Doing: ").append(progress).append(".\n");
        if (s != null) {
            out.append("Gear: pickaxe tier ").append(s.pickaxeTier()).append(" (").append(s.pickaxes()).append(" carried), sword tier ").append(s.swordTier())
                    .append(s.shields() > 0 ? ", a shield" : ", no shield").append(s.chestArmorTier() > 0 ? ", a chestplate" : ", no armour").append(".\n");
            out.append("Materials: ").append(s.woodUnits()).append(" planks' worth of wood, ").append(s.sticks()).append(" sticks, ")
                    .append(s.cobble()).append(" cobblestone, ").append(s.coal()).append(" coal, ").append(s.rawIron()).append(" raw iron, ")
                    .append(s.ironIngots()).append(" iron ingots, ").append(s.emptyBuckets() + s.waterBuckets()).append(" buckets, ")
                    .append(s.diamonds()).append(" diamonds, ").append(s.obsidian()).append(" obsidian.\n");
        }
        out.append("Deaths so far: ").append(deathCount).append(". No-progress spells: ").append(stuckSpells).append(".\n");
        out.append("Stance in force: ").append(stance()).append(", focus ").append(focus()).append(".\n");
        out.append("Habits kept in this world: ").append(habits.isEmpty() ? "none" : habits.toString()).append(".\n");
        if (!events.isEmpty()) {
            out.append("Recent events, oldest first:\n");
            for (String e : events) out.append("- ").append(e).append('\n');
        }
        if (!dangerZones.isEmpty()) {
            out.append("Danger zones:");
            for (DangerZone zone : dangerZones) {
                if (zone.until() > lastTick) out.append(' ').append(zone.center().toShortString().replace(",", "")).append(';');
            }
            out.append('\n');
        }
        if (!memory.isEmpty()) {
            out.append("Your notes from earlier in this world:\n");
            for (String note : memory) out.append("- ").append(note).append('\n');
        }
        return out.toString();
    }
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
        if (task != null && completed && "native_goto".equals(task.skill()) && task.label().endsWith(": back the way it came")) {
            retraceDone = true;
            retraceFrom = lastFeet; // Where it stood when the walk was chosen.
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
        if (task != null && completed && task.label().startsWith("Plant a sapling at") && position(task.args()) != null) {
            planted.add(position(task.args()).asLong());
        }
        if (task != null && completed && "native_break".equals(task.skill()) && task.label().contains(": clear ") && position(task.args()) != null) {
            if (dug.size() > 20_000) dug.clear();
            dug.add(position(task.args()).asLong());
        }
        if (task != null && completed && task.label().endsWith(": advance") && position(task.args()) != null) {
            if (tunnelled.size() > 20_000) tunnelled.clear();
            tunnelled.add(position(task.args()).asLong());
        }
        if (task != null && completed && task.label().equals("Jump down from the ledge") && position(task.args()) != null) {
            // A last resort taken once: landing there again (bobbing in the same pool, say) would only loop.
            BlockPos landed = position(task.args());
            for (BlockPos near : BlockPos.iterate(landed.add(-2, -2, -2), landed.add(2, 2, 2))) {
                blockedUntil.merge(near.asLong(), tick + 1200, Math::max);
            }
        }
        if (task != null && completed && task.label().startsWith("Hole up: seal") && position(task.args()) != null) {
            blockedUntil.merge(position(task.args()).asLong(), tick + CAVE_DANGER_TICKS, Math::max); // Leave the wall standing.
        }
        if (task != null && completed) repeatFailures.remove(task);
        if (task != null && completed && ("native_cast".equals(task.skill()) || task.label().startsWith("Cast: fill"))) {
            castFailedFrom = null;
            castFailedStands.clear();
        }
        if (completed || task == null) return;
        // The same task failing again and again is set aside for a while, whatever it is, so the plan moves on.
        if (repeatFailures.merge(task, 1, Integer::sum) >= 3) {
            repeatFailures.remove(task);
            setAside.put(task, tick + 1200);
        }
        BlockPos pos = position(task.args());
        // A lava fill refused from where the player stood is first tried again from closer up; the source is set
        // aside only when that fails too, or a pool would run out of sources it will use.
        boolean firstRefusedFill = task.label().startsWith("Cast: fill") && pos != null && fillRefusals.merge(pos.asLong(), 1, Integer::sum) < 2;
        // An unreachable trunk or ore column usually stays unreachable; skip its neighbours too. A pool with no route to
        // it from here has no route to the next cell of it either: live run 61, below a lake, tried 1,200 of its cells.
        boolean walk = "native_goto".equals(task.skill()) && task.label().startsWith("Walk to");
        boolean pool = walk && (task.label().startsWith("Walk to water") || task.label().startsWith("Walk to lava"));
        if (pos != null && !firstRefusedFill) avoid(pos, walk, pool, tick);
        if ("native_goto".equals(task.skill()) && task.label().startsWith("Explore")) {
            exploreFailures++;
            exploreHeading = null;
        }
        if (task.label().equals("Run from creeper")) runFailedUntil = tick + 200;
        if (task.label().startsWith("Back off")) backOffFailedUntil = tick + 100; // No way off: cover instead, for now.
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
        if ("native_cast".equals(task.skill())) castFailedStands.add(lastFeet.asLong());
    }

    void placedWorkstation(BlockPos pos) { ownWorkstations.add(pos.asLong()); }

    /** Remembers where the player died, like a person would, so it can try once to pick its things back up. */
    void died(BlockPos pos, ServerWorld world, long tick, boolean killedByMob) {
        died(pos, world, tick, killedByMob, killedByMob ? "killed by a mob" : "died");
    }

    void died(BlockPos pos, ServerWorld world, long tick, boolean killedByMob, String cause) {
        deathCount++;
        if (deathsSeen.size() >= 8) deathsSeen.removeFirst();
        deathsSeen.addLast(new Death(pos.toImmutable(), cause, stage));
        event("died at " + pos.toShortString().replace(",", "") + ": " + cause + " (stage " + stageLabel(stage) + ")");
        // Counters start again from an empty inventory: progress is measured afresh.
        best.clear();
        stuckClock = 0;
        stuckLevel = 0;
        relocate = null;
        obsidianSite = null;
        exploreHeading = null;
        approaches.clear();
        snug = null; // A new life starts somewhere else, walled in nowhere.
        trail.clear();
        holedUpSince = -1;
        holdingSince = -1;
        fightHeldUntil = -1;
        retreatHeldUntil = -1;
        // Lava burns the drops; anything else stays on the ground for about five minutes. Whatever killed the
        // player is probably still standing over them, so a mob kill is not worth walking back into.
        boolean burned = world.getFluidState(pos).isIn(FluidTags.LAVA) || world.getBlockState(pos).isOf(Blocks.FIRE);
        dropSite = burned || killedByMob ? null : pos.toImmutable();
        dropSiteUntil = tick + 5_000;
        if (killedByMob) markDanger(pos, tick + DANGER_TICKS);
    }

    /**
     * Where mobs killed the player (for a day) or outmatched it underground (for a few minutes): it does not go back
     * to caves or ore there meanwhile, as a player would not.
     */
    private record DangerZone(BlockPos center, long until) { }
    private final java.util.ArrayList<DangerZone> dangerZones = new java.util.ArrayList<>();
    private static final int DANGER_RADIUS = 12;
    private static final long DANGER_TICKS = 24_000;
    private static final long CAVE_DANGER_TICKS = 6_000;

    private boolean dangerous(BlockPos pos) {
        for (DangerZone zone : dangerZones) {
            if (zone.until() > lastTick && zone.center().getSquaredDistance(pos) < DANGER_RADIUS * DANGER_RADIUS) return true;
        }
        return false;
    }

    /** Within two dozen blocks across of a danger zone at or below this level: no staircase goes down there. */
    private boolean dangerBelow(BlockPos pos) {
        for (DangerZone zone : dangerZones) {
            if (zone.until() <= lastTick || zone.center().getY() > pos.getY() + 4) continue;
            double dx = zone.center().getX() - pos.getX(), dz = zone.center().getZ() - pos.getZ();
            if (dx * dx + dz * dz < 24 * 24) return true;
        }
        return false;
    }

    private void markDanger(BlockPos pos, long until) {
        for (int i = 0; i < dangerZones.size(); i++) {
            DangerZone zone = dangerZones.get(i);
            if (zone.center().getSquaredDistance(pos) >= 6 * 6) continue;
            if (zone.until() < until) dangerZones.set(i, new DangerZone(zone.center(), until)); // The same place: extend it.
            return;
        }
        if (dangerZones.size() >= 8) dangerZones.removeFirst();
        dangerZones.add(new DangerZone(pos.toImmutable(), until));
        event("danger marked at " + pos.toShortString().replace(",", ""));
    }

    /**
     * Skips a target that failed. Repeat failures at the same place back off longer, so a fast-ticking server
     * cannot make the player cycle through the same few unreachable targets.
     */
    private void avoid(BlockPos pos, boolean column, long tick) {
        avoid(pos, column, false, tick);
    }

    /** As above; {@code pool} skips the part of a pool around it too (six blocks each way, two up and down). */
    private void avoid(BlockPos pos, boolean column, boolean pool, long tick) {
        int count = failures.merge(pos.asLong(), 1, Integer::sum);
        long until = tick + (2400L << Math.min(count - 1, 5));
        blockedUntil.merge(pos.asLong(), until, Math::max); // The exact target, whatever kind it was.
        if (!column) return;
        int across = pool ? 6 : 1, up = pool ? 2 : 6;
        for (int dy = -up; dy <= up; dy++) for (int dx = -across; dx <= across; dx++) for (int dz = -across; dz <= across; dz++) {
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
            if (relocate == null && stuckClock > STUCK_TICKS) startRelocating(groundBelow(body.entity()));
            Optional<Task> away = relocateStep(body);
            // A step that keeps failing ends this move elsewhere, as it would end any plan step; the next stuck spell tries another heading.
            if (away.isPresent() && setAside.getOrDefault(away.get(), Long.MIN_VALUE) > tick) {
                relocate = null;
                away = Optional.empty();
            }
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

    /**
     * Safety, shelter and honest waiting: time spent on these is not time stuck. A sapling takes many minutes to
     * grow, and on a sky island nothing else gives wood (sky-island run 1's watchdog fired while it waited).
     */
    private static boolean passive(Task task) {
        String label = task.label();
        return label.equals("Wait for daylight") || label.equals("Rest to heal") || label.startsWith("Fight")
                || label.startsWith("Take cover") || label.startsWith("Run from") || label.startsWith("Back off")
                || label.startsWith("Close in") || label.startsWith("Seal the shelter") || label.startsWith("Dig in")
                || label.startsWith("Place a torch") || label.startsWith("Get out of the water") || label.startsWith("Hole up")
                || label.startsWith("Hold the passage") || label.endsWith(" by hand") || label.startsWith("Wait for the sapling");
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

    /**
     * Pick somewhere else to work from: a new heading two dozen blocks off, and every third time home. A sky island
     * has no somewhere else, and a tunnel across it only spends the ground it stands on (sky-island run 1 dug its
     * top layer away): there, everything set aside is looked at again from where the player stands.
     */
    private void startRelocating(boolean elsewhere) {
        stuckSpells++;
        event("no progress for a while near " + lastFeet.toShortString().replace(",", "") + " (" + progress + ")");
        stuckLevel++;
        stuckClock = 0;
        tunnelled.clear();
        if (!elsewhere) {
            blockedUntil.clear();
            unreachableUntil.clear();
            unreachableFrom.clear();
            failures.clear();
            approaches.clear();
            repeatFailures.clear();
            setAside.clear();
            generatorTried.clear();
            exploreStalls = 0;
            KindredMod.LOGGER.info("Goal made no progress for {} ticks near {} with nowhere else to go; looking again at what was set aside (attempt {})",
                    STUCK_TICKS, lastFeet.toShortString(), stuckLevel);
            return;
        }
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
        // Water or lava on every side of a tunnel (a flooded cave): up and out instead (live run 54 tried 18 times).
        if (dig.isEmpty()) dig = climb(body, "climb away from here");
        if (dig.isEmpty()) relocate = null; // Nothing works that way either; the next stuck spell tries another.
        return dig;
    }

    private Optional<Task> plan(NativePlayerBody body, EditPolicy.ServerPolicy policy, long tick) {
        lastTick = tick;
        lastFeet = body.entity().getBlockPos().toImmutable();
        if (trail.isEmpty() || !trail.peekLast().equals(lastFeet)) {
            if (trail.size() >= TRAIL_LENGTH) trail.removeFirst();
            trail.addLast(lastFeet);
        }
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
        if (loggedStage != stage) {
            if (loggedStage != null) event("stage " + stageLabel(loggedStage) + " -> " + stageLabel(stage));
            loggedStage = stage;
        }
        noteHappenings(player);
        var steps = new java.util.ArrayList<>(PortalRunPlanner.plan(snapshot, facts, preferences()));
        // Opportunistic upkeep the plan does not order: water while a source is close, a sword before going deep.
        // Not with a bucket that holds lava, nor while a generator is being built: its own water is not to be taken.
        // Close means close: a lake far overhead is a climb, not a detour (live run 53 tried one from 27 blocks below
        // for minutes).
        if (snapshot.emptyBuckets() > snapshot.lavaBuckets() && snapshot.waterBuckets() == 0 && obsidianSite == null && generatorPlan == null
                && knowledge.nearest(player.getServerWorld(), WorldKnowledge.Kind.WATER, player.getBlockPos(),
                        p -> !blocked(p) && !unreachable(p) && !generatorCell(p) && p.getSquaredDistance(player.getBlockPos()) < 24 * 24
                                && Math.abs(p.getY() - player.getBlockY()) <= 6).isPresent()) {
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
        // A fight or a retreat, once chosen, is held for a few seconds, as a player commits to one: a zombie knocked a
        // step back by a hit is no reason to turn and wall off, nor a skeleton stepping out of view one to walk back
        // into its fire (live run 58's three deaths came as the goal switched between them every second). Only a
        // fall below six health ends a fight early; a retreat turns to fight only when there is nowhere to back off to.
        boolean fighting = lastTick < fightHeldUntil && player.getHealth() >= 6;
        boolean retreating = lastTick < retreatHeldUntil;
        Optional<Task> hole = fighting ? Optional.empty() : holeUp(body);
        if (hole.isPresent()) {
            retreatHeldUntil = Math.max(retreatHeldUntil, lastTick + RETREAT_HOLD);
            progress = stageLabel(stage) + ": outmatched - " + hole.get().label();
            return hole;
        }
        Optional<Task> defense = defend(body);
        if (defense.isPresent() && retreating && defense.get().label().startsWith("Fight") && !creeperTask(defense.get())
                && finishable(player) == null) {
            Optional<Task> away = backOff(player);
            if (away.isPresent()) defense = away;
        }
        if (defense.isPresent()) {
            String label = defense.get().label();
            if (label.startsWith("Fight") || label.startsWith("Close in")) {
                fightHeldUntil = lastTick + FIGHT_HOLD;
                if (foe != null && !foe.equals(reportedFoe)) {
                    reportedFoe = foe;
                    event(label.toLowerCase(java.util.Locale.ROOT) + " at " + lastFeet.toShortString().replace(",", "") + ", health "
                            + Math.round(player.getHealth()) + "/20");
                }
            }
            else if (label.startsWith("Back off") || label.startsWith("Take cover") || label.startsWith("Run from")) {
                retreatHeldUntil = Math.max(retreatHeldUntil, lastTick + RETREAT_HOLD);
            }
            progress = stageLabel(stage) + ": fight back - " + label;
            return defense;
        }
        // Hurt below ground with nothing to eat, so nothing to heal with: up to where the animals are, rather than on
        // with the work (live run 58 went back to exploring a cave, out of food and below six health, and was shot).
        boolean dark = player.getServerWorld().isNight() && player.getServerWorld().getDimension().hasSkyLight();
        if (player.getHealth() < 10 && snapshot.edibleFood() == 0 && player.getHungerManager().getFoodLevel() < 18 && underground(player) && !dark) {
            Optional<Task> up = wayUp(body, "Climb toward the surface for food");
            if (up.isPresent()) {
                progress = stageLabel(stage) + ": hurt and out of food - " + up.get().label();
                return up;
            }
        }
        // Advised to surface and recover: up out of the dark first, in daylight, then heal fully before going on.
        boolean recovering = stance() == StrategyAdvice.Stance.SURFACE_AND_RECOVER;
        if (recovering && underground(player) && !dark) {
            Optional<Task> up = wayUp(body, "Climb to the surface to recover");
            if (up.isPresent()) {
                progress = stageLabel(stage) + ": advised to recover - " + up.get().label();
                return up;
            }
        }
        // Hurt with nothing after it: heal before going on (food in hand is eaten by the survival tasks).
        if (player.getHealth() < (recovering ? 18 : 10) && (player.getHungerManager().getFoodLevel() >= 18 || snapshot.edibleFood() > 0)) {
            progress = stageLabel(stage) + ": heal before going on";
            return Optional.of(new Task("native_wait", Map.of("seconds", "4"), "Rest to heal"));
        }
        // Up and healed: the advice is done with; the rest of its time is spent playing safe, not climbing out again.
        if (recovering && !underground(player) && player.getHealth() >= 18) {
            advice = advice.withStance(StrategyAdvice.Stance.PLAY_SAFE);
            event("recovered on the surface; playing safe for the rest of the advice");
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
        // Nights on the surface belong to skeletons: get below ground, and leave surface jobs for daylight. A player that
        // learned to shelter early counts the last of the afternoon as night already.
        var here = player.getServerWorld();
        boolean night = here.getDimension().hasSkyLight() && (here.isNight()
                || habits.contains(PlanPreferences.Habit.SHELTER_EARLY) && here.getTimeOfDay() % 24_000 >= DUSK && here.getTimeOfDay() % 24_000 < 13_000);
        if (night && !sheltered(player)) {
            Optional<Task> dig = digDown(body, player.getBlockY() - 3, "Dig in for the night");
            if (dig.isPresent()) {
                progress = stageLabel(stage) + ": shelter for the night - " + dig.get().label();
                return dig;
            }
        }
        // A sky island's ground is a few blocks deep: its night shelter is a pit, sealed over, and waited in, rather
        // than one hole after another across the island (sky-island run 4 dug half a dozen, broke into its edge wall
        // and went over the side).
        if (night && !groundBelow(player) && sheltered(player)) {
            Optional<Task> seal = player.isOnGround() ? seal(body) : Optional.empty();
            if (seal.isPresent()) {
                progress = stageLabel(stage) + ": shelter for the night - " + seal.get().label();
                return seal;
            }
            progress = stageLabel(stage) + ": wait for daylight in the shelter";
            return Optional.of(new Task("native_wait", Map.of("seconds", "4"), "Wait for daylight"));
        }
        // Morning on an island: out of the night's pit first. Nothing is built or found from inside it, and the layer
        // it stands on there has the void under it (sky-island run 8 waited ten minutes in one).
        if (!night && !groundBelow(player) && inPit(player)) {
            Optional<Task> out = climb(body, "Climb out of the night shelter");
            if (out.isPresent()) {
                progress = stageLabel(stage) + ": morning - " + out.get().label();
                return out;
            }
        }
        // On an island the saplings its tree's leaves drop are picked up at night too: a night outlasts a drop.
        if (night && !groundBelow(player)) {
            Optional<Task> loose = pickUpLoose(player);
            if (loose.isPresent()) {
                progress = stageLabel(stage) + ": " + loose.get().label();
                return loose;
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
            if (night && step.action() == PortalRunPlanner.Action.SEEK_FOOD) continue; // Looking about for animals is daylight work.
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
        // Not down among mobs without a shield: a player listens before dropping into a cave (live run 52 jumped in
        // beside a creeper).
        BlockPos landing = best;
        if (!shielded(player) && !world.getEntitiesByClass(HostileEntity.class, new Box(landing).expand(12, 6, 12),
                mob -> mob.isAlive() && !(mob instanceof EndermanEntity)).isEmpty()) return Optional.empty();
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
            // Its own table, just used: whatever was set aside around here, this is in reach (live run 57 left one in
            // a branch tunnel where an earlier target had failed, and later lost its last pickaxe with no table).
            BlockPos feet = player.getBlockPos();
            var table = knowledge.nearest(world, WorldKnowledge.Kind.CRAFTING_TABLE, feet,
                    p -> p.getSquaredDistance(feet) <= 36 && ownWorkstations.contains(p.asLong()));
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
                obsidian = 0, buckets = 0, lavaBuckets = 0, water = 0, fns = 0, tables = 0, furnaces = 0, saplings = 0, food = 0, pick = 0, pickaxes = 0, sword = 0,
                chest = 0, shields = 0;
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
            else if (item == Items.BUCKET) buckets += n;
            else if (item == Items.LAVA_BUCKET) {
                buckets += n; // Lava carried to a cast or a generator empties into it.
                lavaBuckets += n;
            }
            else if (item == Items.WATER_BUCKET) water += n;
            else if (item == Items.FLINT_AND_STEEL) fns += n;
            else if (item == Items.CRAFTING_TABLE) tables += n;
            else if (item == Items.FURNACE) furnaces += n;
            else if (item == Items.SHIELD) shields += n; // The inventory's last slot is the off hand.
            else if (stack.isIn(ItemTags.SAPLINGS)) saplings += n;
            if (stack.contains(DataComponentTypes.FOOD) && safeFood(stack)) food += n;
            pick = Math.max(pick, stack.isIn(ItemTags.PICKAXES) ? tier(item) : 0);
            if (stack.isIn(ItemTags.PICKAXES) && tier(item) >= 2) pickaxes += n;
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
            if (furnace.getStack(2).isOf(Items.CHARCOAL)) coal += furnace.getStack(2).getCount(); // As the planner counts it.
        }
        // Obsidian already set into the frame still counts toward the ten.
        if (portalSite != null) {
            obsidian += frameObsidian(player.getServerWorld(), portalSite);
            cobble += frameCorners(player.getServerWorld(), portalSite); // Placed corners count toward the four too.
        }
        boolean framed = portalSite != null && portalComplete(player.getServerWorld(), portalSite);
        boolean lit = portalSite != null && player.getServerWorld().getBlockState(portalSite.at(1, 1)).isOf(Blocks.NETHER_PORTAL);
        return new PortalRunSnapshot(logs, planks, sticks, cobble, coal, rawIron, iron, diamonds, flint, gravel, obsidian, buckets, lavaBuckets,
                water, fns, tables, furnaces, saplings, food, pick, pickaxes, sword, chest, shields, tableNearby, furnacePos.isPresent(), furnaceSmelting, furnaceOutput,
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
            // Advised food first and none in sight: out to the surface by day, then walk out and look. Not over the void:
            // an island's animals are the ones on it.
            case SEEK_FOOD -> !groundBelow(player) ? Optional.<Task>empty()
                    : underground(player) ? wayUp(body, "Climb out to look for animals") : explore(body, "animals");
            // Advised wood first: a tree in sight, and nothing more; looking for one is the route's own step.
            case CUT_TREES -> gather(body, WorldKnowledge.Kind.LOG, 32, "logs");
            case GATHER_LOGS -> gather(body, WorldKnowledge.Kind.LOG, 64, "logs")
                    .or(() -> underground(player) ? wayUp(body, "Climb toward the surface for logs")
                            // Not when already there: that walk ends where it starts, over and over.
                            .or(() -> home == null || home.getSquaredDistance(lastFeet) <= 4 * 4 ? Optional.empty()
                                    : Optional.of(go(home, 3, true, "Head back up toward home")))
                            : explore(body, "logs"));
            // Stone is everywhere below: take what is close, otherwise dig for it rather than walk to a far cave wall.
            // Over a sky island there is no stone below to dig for: only what is in sight, or the generator's.
            case GATHER_STONE -> mineGenerator(body).or(() -> generatorPlan != null ? buildGenerator(body) : Optional.<Task>empty())
                    .or(() -> gather(body, WorldKnowledge.Kind.STONE, groundBelow(player) ? 6 : PortalRunPlanner.ISLAND_REACH, "stone"))
                    .or(() -> groundBelow(player) ? digDown(body, player.getBlockY() - 6, "Dig for stone") : Optional.<Task>empty())
                    .or(() -> search(body, "stone"));
            case FILL_LAVA -> fillLava(body);
            case BUILD_GENERATOR -> buildGenerator(body);
            case PLANT_SAPLING -> plantSapling(body);
            case AWAIT_TREE -> awaitTree(body);
            case GATHER_SAPLINGS -> gatherSaplings(body);
            // Ore in view first, then caves it has seen, which show far more ore than a tunnel; digging comes last.
            case MINE_COAL -> gather(body, WorldKnowledge.Kind.COAL_ORE, oreRadius(player, 32), "coal ore").or(() -> gather(body, WorldKnowledge.Kind.LOG, 48, "logs for fuel"))
                    .or(() -> cave(body, "coal", COAL_TARGET)).or(this::lookAround)
                    // Coal is scarce deep down: climb toward where it is common before tunnelling for it.
                    .or(() -> player.getBlockY() < COAL_TARGET - 12 ? climb(body, "Climb toward coal") : Optional.<Task>empty())
                    .or(() -> groundBelow(player) ? tunnel(body, "Tunnel for coal") : Optional.<Task>empty());
            case MINE_IRON -> gather(body, WorldKnowledge.Kind.IRON_ORE, oreRadius(player, 40), "iron ore")
                    .or(() -> cave(body, "iron", IRON_TARGET)).or(this::lookAround).or(() -> descendOrTunnel(body, IRON_TOP, IRON_TARGET, "iron"));
            case MINE_DIAMOND -> gather(body, WorldKnowledge.Kind.DIAMOND_ORE, oreRadius(player, 40), "diamond ore")
                    .or(() -> gather(body, WorldKnowledge.Kind.IRON_ORE, 6, "iron ore on the way"))
                    .or(() -> cave(body, "diamonds", DIAMOND_TARGET))
                    .or(this::lookAround).or(() -> descendOrTunnel(body, DIAMOND_TOP, DIAMOND_TARGET, "diamonds"));
            case CRAFT -> craft(body, step.item());
            case PLACE_TABLE -> placeItem(body, Items.CRAFTING_TABLE, "Place a crafting table");
            case PLACE_FURNACE -> placeItem(body, Items.FURNACE, "Place a furnace");
            case SMELT -> "log".equals(step.item()) ? charcoal(body) : smelt(body);
            case AWAIT_SMELT -> Optional.of(new Task("native_wait", Map.of("seconds", "3"), "Wait by the furnace"));
            // Water just poured for obsidian guards the mining: mine what it hardened before taking it back.
            case FILL_WATER -> (obsidianSite != null
                    ? obsidian(body).or(() -> gather(body, WorldKnowledge.Kind.OBSIDIAN, 8, "obsidian", true)) : Optional.<Task>empty())
                    // No water known down here: up to the surface, where lakes and rivers are, rather than tunnel about
                    // blind at depth (live run 55 met a skeleton doing that at Y=12).
                    .or(() -> fillWater(body)).or(() -> underground(player) ? wayUp(body, "Climb toward the surface for water") : Optional.<Task>empty())
                    .or(() -> search(body, "water"));
            case MAKE_OBSIDIAN -> gather(body, WorldKnowledge.Kind.OBSIDIAN, 32, "obsidian", true).or(() -> obsidian(body))
                    .or(() -> cave(body, "lava", DIAMOND_TARGET))
                    .or(this::lookAround).or(() -> descendOrTunnel(body, DIAMOND_TOP, DIAMOND_TARGET, "lava"));
            case GATHER_FLINT -> flint(body);
            case BUILD_PORTAL -> buildPortal(body);
            case IGNITE_PORTAL -> ignite(body);
            case CAST_PORTAL -> castPortal(body);
        };
    }

    /**
     * Methods the campaign can carry out where the player is; the planner never chooses one outside this set. Stone
     * is dug where there is ground below; over the void of a sky island it comes from a cobblestone generator.
     */
    private static Set<WorldFacts.Method> methods(ServerPlayerEntity player) {
        var methods = java.util.EnumSet.of(WorldFacts.Method.MINE_OBSIDIAN, WorldFacts.Method.CAST_OBSIDIAN);
        if (groundBelow(player)) {
            methods.add(WorldFacts.Method.DIG_FOR_STONE);
        } else {
            // A sky island's trees are all there will be unless they are replanted.
            methods.add(WorldFacts.Method.COBBLE_GENERATOR);
            methods.add(WorldFacts.Method.REPLANT);
        }
        return methods;
    }

    /** A floor at the bottom of the world under the player: bedrock in an ordinary world, nothing under a sky island. */
    private static boolean groundBelow(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        return !world.getBlockState(new BlockPos(player.getBlockX(), world.getBottomY(), player.getBlockZ())).isAir();
    }
    private static final Map<WorldFacts.Source, WorldKnowledge.Kind> SOURCES = Map.of(
            WorldFacts.Source.TREE, WorldKnowledge.Kind.LOG, WorldFacts.Source.STONE, WorldKnowledge.Kind.STONE,
            WorldFacts.Source.COAL_ORE, WorldKnowledge.Kind.COAL_ORE, WorldFacts.Source.IRON_ORE, WorldKnowledge.Kind.IRON_ORE,
            WorldFacts.Source.DIAMOND_ORE, WorldKnowledge.Kind.DIAMOND_ORE, WorldFacts.Source.LAVA, WorldKnowledge.Kind.LAVA,
            WorldFacts.Source.WATER, WorldKnowledge.Kind.WATER, WorldFacts.Source.GRAVEL, WorldKnowledge.Kind.GRAVEL,
            WorldFacts.Source.CAVE, WorldKnowledge.Kind.CAVE, WorldFacts.Source.LEAVES, WorldKnowledge.Kind.LEAVES);
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
        boolean below = underground(player);
        for (var source : SOURCES.entrySet()) {
            // Trees count only from up among them: from below ground, getting back up to them is a trip, not a detour.
            if (below && source.getKey() == WorldFacts.Source.TREE) continue;
            // Only sources it could still go to: a tree across water it failed to reach is not a tree in sight, and
            // nor are logs left hanging out of reach (sky-island run 12 explored the void for nine minutes for those).
            boolean tree = source.getKey() == WorldFacts.Source.TREE;
            knowledge.nearest(world, source.getValue(), feet, p -> !blocked(p) && !unreachable(p) && !generatorCell(p)
                            && (!tree || trunkFoot(world, p).isPresent()))
                    .ifPresent(pos -> nearest.put(source.getKey(), (int) Math.round(Math.sqrt(pos.getSquaredDistance(feet)))));
        }
        // A generator counts from the moment its water is in: the lava that finishes it is already in hand.
        Generator g = generator != null ? generator : generatorPlan;
        if (g != null && (generator != null || still(world, g.water(), FluidTags.WATER))) {
            nearest.put(WorldFacts.Source.GENERATOR, (int) Math.round(Math.sqrt(g.cobble().getSquaredDistance(feet))));
        }
        nearestSapling(player).ifPresent(sapling -> nearest.put(WorldFacts.Source.SAPLING, (int) Math.round(Math.sqrt(sapling.getSquaredDistance(feet)))));
        facts = new WorldFacts(nearest, methods(player));
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
        // Without a shield to raise, only where no mob can be heard: open caves are where skeletons ended most early
        // lives (live runs 39 to 51). A player hears zombies and skeletons through rock; the first iron otherwise
        // comes from its own tunnels, which a cave they break into is walled off from.
        boolean shield = shielded(body.entity());
        if (!shield && !quietAround(world, lastFeet)) return Optional.empty();
        // Not hurt, either: players heal before going into a cave, not after. Nor while advised to play safe.
        if (body.entity().getHealth() < 14 || stance() == StrategyAdvice.Stance.PLAY_SAFE || habits.contains(PlanPreferences.Habit.NO_CAVES)) {
            return Optional.empty();
        }
        BlockPos feet = lastFeet;
        BlockPos anchor = home == null ? feet : home;
        int gapNow = Math.abs(feet.getY() - targetY);
        // About eight blocks on is a good stride: far enough to open new views, near enough to stay on known ground.
        return knowledge.best(world, WorldKnowledge.Kind.CAVE,
                        pos -> Math.abs(Math.hypot(pos.getX() - feet.getX(), pos.getZ() - feet.getZ()) - 8) + 1.5 * Math.abs(pos.getY() - targetY),
                        pos -> !visitedCaves.contains(BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2))
                                && !blocked(pos) && !unreachable(pos) && !dangerous(pos) && pos.getSquaredDistance(feet) >= 4
                                && (shield || quietAround(world, pos))
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
            WorldFacts known = facts(player);
            int keptSticks = PortalRunPlanner.sticksReserved(snapshot, PortalRunPlanner.frameMethod(snapshot, known),
                    known.can(WorldFacts.Method.DIG_FOR_STONE));
            // About fifteen iron to smelt through the iron stage: two coal stay for the furnace until then.
            int keptCoal = stage.ordinal() <= PortalRunPlanner.Stage.IRON.ordinal() ? 2 : 0;
            if (coal <= keptCoal) return Optional.empty();
            if (snapshot.sticks() > keptSticks) return craft(body, "torch");
            // Not from the planks held back for a shield or a spare table.
            return snapshot.planks() >= 2 + PortalRunPlanner.woodKept(snapshot, known) ? craft(body, "stick") : Optional.empty();
        }
        if (torch >= 9) return Optional.of(swapToHotbar(body, torch, "Ready a torch"));
        // Never in the cell ahead, whose floor the next step digs away (live run 56 placed 28 torches that way, each
        // dropping off as the staircase went on); in a one-wide staircase, on the step behind and above.
        var spots = new java.util.ArrayList<BlockPos>();
        for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            if (direction != digDirection) spots.add(feet.offset(direction));
        }
        spots.add(feet.up().offset(digDirection.getOpposite()));
        for (BlockPos spot : spots) {
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

    /** Break the block the player's head (or feet) is stuck in, as a player digs out of gravel that fell on it. */
    private Optional<Task> digOut(NativePlayerBody body) {
        var player = body.entity();
        if (!player.isInsideWall()) return Optional.empty();
        var world = player.getServerWorld();
        // The same thin slab around the eyes that suffocation tests, which reaches into the next cell when the player
        // stands near its edge; then the feet.
        float width = player.getWidth() * 0.8F;
        var cells = new java.util.ArrayList<BlockPos>();
        BlockPos.stream(Box.of(player.getEyePos(), width, 1.0E-6, width)).forEach(cell -> cells.add(cell.toImmutable()));
        cells.sort(java.util.Comparator.comparingDouble(cell -> player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(cell))));
        cells.add(player.getBlockPos());
        for (BlockPos cell : cells) {
            if (world.getBlockState(cell).getCollisionShape(world, cell).isEmpty() || !breakableNow(body, cell)) continue;
            return Optional.of(clearAt(cell, "Dig out of the wall at " + cell.toShortString()));
        }
        return Optional.empty();
    }

    /** A block this player could break right now from where it stands. */
    private boolean breakableNow(NativePlayerBody body, BlockPos pos) {
        return breakableNow(body, pos, false);
    }

    private boolean breakableNow(NativePlayerBody body, BlockPos pos, boolean guarded) {
        var player = body.entity();
        if (eyeDistance(player, pos) > REACH || !body.miningEligible(pos)) return false;
        if (RoamingEdits.breakDenial(player, pos, policy, guarded) != null) return false;
        return NativeBreakAction.bestTool(body, player.getServerWorld().getBlockState(pos)) != -2;
    }

    /** Rock a player with no pickaxe at all can still punch through: no drop, but a way out. */
    private boolean handClearable(NativePlayerBody body, BlockPos pos) {
        var player = body.entity();
        var world = player.getServerWorld();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isIn(ItemTags.PICKAXES)) return false;
        BlockState state = world.getBlockState(pos);
        if (state.getHardness(world, pos) > 3.0f || eyeDistance(player, pos) > REACH || !body.miningEligible(pos)) return false;
        return RoamingEdits.breakDenial(player, pos, policy, false) == null;
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
        Predicate<BlockPos> usable = p -> !blocked(p) && !unreachable(p) && !dangerous(p) && !p.equals(support) && p.getSquaredDistance(feet) <= radius * radius
                && (kind != WorldKnowledge.Kind.OBSIDIAN || madeFromLava(player.getServerWorld(), p))
                && harvestable(body, p) && player.getServerWorld().canPlayerModifyAt(player, p)
                && (kind != WorldKnowledge.Kind.LOG || p.getY() <= feet.getY() + TRUNK_REACH && trunkFoot(player.getServerWorld(), p).isPresent());
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
            // Plans are re-evaluated often; only finished walks count as approaches. A walk that ended in reach without
            // a view of it (ore it cannot harvest yet in the way) closes in square beside it the second time.
            int tries = approaches.getOrDefault(pos.asLong(), 0);
            if (tries < 2) {
                // A log high over a cut trunk is further from any feet than a walk's reach: walk to where the trunk stood.
                var world = player.getServerWorld();
                BlockPos foot = kind == WorldKnowledge.Kind.LOG ? trunkFoot(world, pos).orElse(pos) : pos;
                if (pos.getY() - foot.getY() > 3 && world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()) {
                    return Optional.of(go(foot, tries == 0 ? 1.5 : 0.0, false, "Walk under the " + what + " at " + pos.toShortString()));
                }
                double range = tries == 0 ? 3.5 : pos.getY() > feet.getY() ? 1.5 : 1.0;
                return Optional.of(go(pos, range, false, "Walk to " + what + " at " + pos.toShortString()));
            }
            approaches.remove(pos.asLong());
            avoid(pos, true, lastTick);
        }
        return Optional.empty();
    }

    /**
     * Where a player stands to chop {@code log}: on solid ground under its trunk, at most {@link #TRUNK_REACH} blocks
     * below it, through the trunk or the cells where its lower logs were cut. Nothing for logs held up by leaves, or
     * hanging higher, which would need a pillar. Planning and gathering both use it, so a log the player would not go
     * for is never a tree in sight either.
     */
    private static Optional<BlockPos> trunkFoot(ServerWorld world, BlockPos log) {
        BlockPos below = log.down();
        for (int depth = 1; depth <= TRUNK_REACH + 1; depth++, below = below.down()) {
            BlockState state = world.getBlockState(below);
            // The rest of a trunk whose bottom logs were cut is still chopped from the ground beneath it.
            if (state.isIn(BlockTags.LOGS) || state.isAir()) continue;
            return !state.getCollisionShape(world, below).isEmpty() && !state.isIn(BlockTags.LEAVES) ? Optional.of(below.up()) : Optional.empty();
        }
        return Optional.empty();
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

    /**
     * Underground, search by tunnelling along the current level; on the surface, walk out and look. Over the void
     * there is nothing to tunnel toward: a cut through a sky island only spends it.
     */
    private Optional<Task> search(NativePlayerBody body, String what) {
        var player = body.entity();
        if (!groundBelow(player)) return explore(player, what);
        if (underground(player)) return tunnel(body, "Tunnel for " + what).or(() -> explore(body, what));
        return explore(body, what).or(() -> tunnel(body, "Tunnel for " + what));
    }

    /**
     * Walk out to look, and when walking keeps ending where it started (hemmed in by leaves, trunks or a drop the
     * routes cannot cross), cut a two-high way through along the heading, as a person would.
     */
    private Optional<Task> explore(NativePlayerBody body, String what) {
        var player = body.entity();
        // Over the void a cut leads nowhere and spends the island.
        if (exploreStalls >= 2 && exploreHeading != null && groundBelow(player)) {
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
        // Over the void there is nothing below to reach: digging would only spend the island.
        if (!groundBelow(body.entity())) return Optional.empty();
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
            // Water or lava under the step: that way down ends in it, so turn before digging anything there. Nor is a
            // step dug that opens onto a drop deeper than the navigator allows, a cave's ceiling: that leaves only a
            // pit (live run 52 dug one on every side and stood stranded between them).
            // Over the void, the island's last layer stays under every step: nothing below it would catch a fall.
            boolean refused = !world.getFluidState(step.down()).isEmpty() || !landingWithin(world, step, feet)
                    || !groundBelow(player) && passable(world, step.down())
                    // Nor down toward a place where mobs killed or cornered it: the staircase turns, or the tunnel
                    // fallback carries it off sideways to dig down elsewhere (live runs 52 and 56 dug back down into
                    // the cave under spawn they had died in).
                    || dangerBelow(step);
            for (BlockPos cell : clear) {
                if (refused) break;
                if (passable(world, cell)) continue;
                if (blocked(cell) || liquidNear(world, cell) || islandEdge(player, cell)) { refused = true; break; }
                if (breakableNow(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString()));
                // A night shelter is dug by hand when there is no pickaxe: slow in rock, but better than waiting in the
                // open (live run 59 died four times at night on the surface just after respawning with nothing).
                if (label.startsWith("Dig in for the night") && handClearable(body, cell)) {
                    return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString() + " by hand"));
                }
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

    /** Solid ground under {@code step}, or under the open cells below it, no deeper than the navigator will drop. */
    private static boolean landingWithin(ServerWorld world, BlockPos step, BlockPos feet) {
        BlockPos landing = step;
        while (passable(world, landing.down()) && landing.getY() > feet.getY() - NativeNavigator.MAX_SAFE_DROP) landing = landing.down();
        return !passable(world, landing.down());
    }

    /**
     * Up toward the surface. With no pickaxe, back the way it came, which it knows is walkable (live run 57 stood
     * at Y=54 for good when its last pickaxe broke climbing out). With one, whichever is cheaper: the walk back, or
     * a fresh staircase up, which is shorter but wears the pickaxe and digs into whatever lies above. Punching up by
     * hand is the last resort, for a player sealed in with no way back.
     */
    private Optional<Task> wayUp(NativePlayerBody body, String label) {
        var player = body.entity();
        boolean pickaxe = false;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size() && !pickaxe; slot++) pickaxe = inventory.getStack(slot).isIn(ItemTags.PICKAXES);
        // Boxed into a hole, every walk out fails at once and sets the way it came aside. Without a pickaxe it
        // pillars out, quicker than punching a staircase by hand; with one it digs its staircase out, as before.
        if (!pickaxe) return pillarOut(body, label).or(() -> retrace(body, label)).or(() -> climb(body, label));
        if (boxedIn(player.getServerWorld(), player.getBlockPos())) return climb(body, label).or(() -> pillarOut(body, label));
        int surface = player.getServerWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, player.getBlockX(), player.getBlockZ());
        int depth = Math.max(1, surface - player.getBlockY());
        // A step of the way back costs about a third of a staircase step (three blocks dug per block climbed).
        boolean walkBack = stepsBackToDaylight(player.getServerWorld()) <= 3 * depth;
        return walkBack ? retrace(body, label).or(() -> climb(body, label)) : climb(body, label).or(() -> retrace(body, label));
    }

    /**
     * A cell on the way it came, four to twelve steps back along its trail and within sixteen blocks, that leaves
     * every one of {@code hunters} at least two blocks further off than now.
     */
    private Optional<BlockPos> trailAwayFrom(List<HostileEntity> hunters) {
        if (trail.size() < 3) return Optional.empty();
        int index = nearestOnTrail();
        var cells = new java.util.ArrayList<>(trail);
        for (int back = 12; back >= 4; back--) {
            int i = index - back;
            if (i < 0) continue;
            BlockPos cell = cells.get(i);
            if (cell.getSquaredDistance(lastFeet) > 16 * 16 || blocked(cell) || dangerous(cell)) continue;
            boolean away = true;
            for (HostileEntity mob : hunters) {
                double now = Math.sqrt(mob.getBlockPos().getSquaredDistance(lastFeet));
                if (Math.sqrt(mob.getBlockPos().getSquaredDistance(cell)) < now + 2) { away = false; break; }
            }
            if (away) return Optional.of(cell);
        }
        return Optional.empty();
    }

    /** Trail steps from where the player stands back to the last place on it under open sky, or a large number. */
    private int stepsBackToDaylight(ServerWorld world) {
        if (trail.isEmpty()) return Integer.MAX_VALUE;
        int index = nearestOnTrail();
        var cells = new java.util.ArrayList<>(trail);
        for (int i = index; i >= 0; i--) if (world.isSkyVisible(cells.get(i).up())) return index - i;
        return Integer.MAX_VALUE;
    }

    /** Index of the trail cell nearest the player, the latest of equals. */
    private int nearestOnTrail() {
        int index = -1, i = 0;
        double best = Double.MAX_VALUE;
        for (BlockPos cell : trail) {
            double d = cell.getSquaredDistance(lastFeet);
            if (d <= best) { best = d; index = i; }
            i++;
        }
        return index;
    }

    /**
     * Back along its own trail: each leg to the highest cell it stood in within a score of blocks, further back along
     * the way it came than where it stands now, or under open sky, so loops in the way in are cut short.
     */
    private Optional<Task> retrace(NativePlayerBody body, String label) {
        var world = body.entity().getServerWorld();
        // A walk back that cannot start completes at once without moving; asked for again it only loops (the
        // staircase fixture, its foot open, stood in a pit for three minutes). Another way out gets half a minute.
        if (retraceDone) {
            retraceDone = false;
            if (retraceFrom != null && retraceFrom.getManhattanDistance(lastFeet) <= 1) retraceStalledUntil = lastTick + 600;
        }
        // Only while it is still there: once it has climbed up or moved away, the way it came is worth a try again.
        if (lastTick < retraceStalledUntil && retraceFrom != null && retraceFrom.getManhattanDistance(lastFeet) <= 1
                && lastFeet.getY() <= retraceFrom.getY()) return Optional.empty();
        if (trail.size() < 2) return Optional.empty();
        int index = nearestOnTrail();
        var cells = new java.util.ArrayList<>(trail);
        BlockPos target = null;
        boolean targetSky = false;
        for (int i = index - 1; i >= 0; i--) {
            BlockPos cell = cells.get(i);
            if (cell.getSquaredDistance(lastFeet) > 20 * 20 || blocked(cell) || dangerous(cell)) continue;
            boolean sky = world.isSkyVisible(cell.up());
            // Open sky first, then height; between equals, the one further back along the way in.
            boolean better = target == null || sky && !targetSky || sky == targetSky && cell.getY() >= target.getY();
            if (better) {
                target = cell;
                targetSky = sky;
            }
        }
        if (target == null || target.getSquaredDistance(lastFeet) < 2) return Optional.empty();
        return Optional.of(go(target, 0.8, true, label + ": back the way it came"));
    }

    /**
     * Out of a hole it can neither walk nor step out of, as a player gets out: a block of its own placed under its
     * feet at the top of a jump, and again until it can walk on. Only with head room to jump into and a filler block
     * to spare; quicker than punching a staircase by hand.
     */
    private Optional<Task> pillarOut(NativePlayerBody body, String label) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        if (!player.isOnGround() || !boxedIn(world, feet) || !passable(world, feet.up(2))) return Optional.empty();
        var inventory = player.getInventory();
        int slot = -1;
        for (int s = 0; s < 36 && slot < 0; s++) if (SEALING.contains(inventory.getStack(s).getItem())) slot = s;
        if (slot < 0) return Optional.empty();
        if (slot >= 9) return Optional.of(swapToHotbar(body, slot, "Ready a block"));
        return Optional.of(new Task("native_pillar", coords(feet, Map.of("slot", String.valueOf(slot))), label + ": pillar up"));
    }

    /** No side to walk out of or step up onto. */
    private static boolean boxedIn(ServerWorld world, BlockPos feet) {
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos next = feet.offset(side);
            if (passable(world, next) && passable(world, next.up())) return false;
            if (passable(world, next.up()) && passable(world, next.up(2)) && passable(world, feet.up(2))) return false;
        }
        return true;
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
            // Nothing to climb onto this way, or a landing it already failed to reach (live run 49 tried one 232 times).
            boolean refused = passable(world, step) || blocked(landing);
            for (BlockPos cell : List.of(feet.up(2), landing, landing.up())) {
                if (refused || passable(world, cell)) continue;
                if (blocked(cell) || liquidNear(world, cell) || islandEdge(player, cell)) { refused = true; break; }
                if (breakableNow(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString()));
                // Its last pickaxe gone below ground: punch the way up, slowly, as a player would (live run 57 broke
                // its last one climbing out and stood at Y 54 for good).
                if (handClearable(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString() + " by hand"));
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
                if (blocked(cell) || liquidNear(world, cell) || islandEdge(player, cell)) { refused = true; break; }
                if (breakableNow(body, cell)) return Optional.of(clearAt(cell, label + ": clear " + cell.toShortString()));
                refused = true;
                break;
            }
            // Walking dug ground again is fine on the way to rock it can dig; pacing a dead-end corridor is not.
            if (!refused && !passable(world, ahead.down()) && !liquidNear(world, ahead) && world.getFluidState(ahead).isEmpty() && !blocked(ahead)
                    && (!tunnelled.contains(ahead.asLong()) || diggableFaceAhead(body, ahead))) {
                return Optional.of(go(ahead, 0.4, false, label + ": advance"));
            }
            // Never a step down into water: wading back out to here and stepping in again would go round for ever.
            if (!refused && passable(world, ahead.down()) && !passable(world, ahead.down(2)) && !blocked(ahead.down())
                    && world.getFluidState(ahead.down()).isEmpty() && !liquidNear(world, ahead.down())) {
                return Optional.of(go(ahead.down(), 0.4, false, label + ": step down"));
            }
            // A pit it dug itself over a drop (a staircase step that broke through a cave's ceiling): fill it and go on.
            if (!refused && passable(world, ahead.down()) && passable(world, ahead.down(2)) && dug.contains(ahead.down().asLong())
                    && world.getFluidState(ahead.down()).isEmpty()) {
                Optional<Task> fill = placeBlock(body, ahead.down(), label + ": fill " + ahead.down().toShortString());
                if (fill.isPresent()) return fill;
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
        // A wooden workstation goes nowhere near a generator's lava: first walk out of its reach.
        if (generator != null && (nearGeneratorLava(player.getBlockPos()) || player.getBlockPos().equals(generator.stand()))) {
            Vec3d away = Vec3d.ofCenter(player.getBlockPos()).subtract(Vec3d.ofCenter(generator.flow())).multiply(1, 0, 1);
            if (away.lengthSquared() < 1.0E-4) away = Vec3d.of(generator.side().getVector());
            away = away.normalize().multiply(5);
            BlockPos target = BlockPos.ofFloored(Vec3d.ofCenter(generator.flow()).add(away)).withY(generator.flow().getY() + 1);
            return Optional.of(go(target, 1.5, true, "Step away from the generator's lava"));
        }
        if (slot >= 9) return Optional.of(swapToHotbar(body, slot, "Ready " + Registries.ITEM.getId(item).getPath().replace('_', ' ')));
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        boolean ground = groundBelow(player);
        for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            // Beside the player, two off, or on a step above (the step behind in a one-wide staircase, where there is
            // no floor beside it and nothing to carve room with once the pickaxe is gone, as in live run 56).
            for (BlockPos spot : List.of(feet.offset(direction), feet.offset(direction, 2), feet.up().offset(direction))) {
                if (!world.getBlockState(spot).isReplaceable() || !world.getFluidState(spot).isEmpty()) continue;
                var floor = world.getBlockState(spot.down()).getCollisionShape(world, spot.down());
                // Not on a wall or a fence: it stands taller than a block, into the spot (the sky island's edge wall).
                if (floor.isEmpty() || floor.getMax(Direction.Axis.Y) > 1.0) continue;
                if (eyeDistance(player, spot) > 4.4 || blocked(spot) || nearGeneratorLava(spot)) continue;
                // Over the void, not at the island's edge: picked up again, the block's drop could slide off it.
                if (!ground && besideTheVoid(world, spot)) continue;
                // Not where the player (standing off the middle of its block) or anything living overlaps it: vanilla
                // refuses that placement, and the island outcrop fixture waited on one such spot for good.
                if (!world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, new Box(spot).contract(0.01), net.minecraft.entity.LivingEntity::isAlive).isEmpty()) continue;
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
        // Nowhere here (on top of a half-mined outcrop, say): step over to the nearest open floor that will take it, and
        // stand right beside it, where it is one of the spots tried above. A walk to within a couple of blocks of it
        // could end where it already stood, with the spot off to one side and still out of the ones tried (the island
        // outcrop fixture failed about one run in ten that way).
        for (BlockPos cell : BlockPos.iterateOutwards(feet, 5, 2, 5)) {
            if (cell.getSquaredDistance(feet) < 4 || !world.getBlockState(cell).isReplaceable() || !world.getFluidState(cell).isEmpty() || blocked(cell)) continue;
            var floor = world.getBlockState(cell.down()).getCollisionShape(world, cell.down());
            if (floor.isEmpty() || floor.getMax(Direction.Axis.Y) > 1.0 || nearGeneratorLava(cell) || !ground && besideTheVoid(world, cell)) continue;
            for (Direction side : Direction.Type.HORIZONTAL) {
                BlockPos stand = cell.offset(side);
                if (stand.equals(feet) || !standable(world, stand) || blocked(stand) || unreachable(stand) || !ground && besideTheVoid(world, stand)) continue;
                return Optional.of(go(stand.toImmutable(), 0.5, false, "Walk to room for a workstation"));
            }
        }
        return Optional.empty();
    }

    /** Beside a column with nothing in it for four blocks down from {@code spot}'s level: an island's edge over the void. */
    private static boolean besideTheVoid(ServerWorld world, BlockPos spot) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos side = spot.offset(direction);
            boolean caught = false;
            for (int down = 0; down <= 4 && !caught; down++) caught = !passable(world, side.down(down));
            if (!caught) return true;
        }
        return false;
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

    /** One log into the furnace with one plank to burn it: charcoal for the iron where there is no coal (F-25c5c). */
    private Optional<Task> charcoal(NativePlayerBody body) {
        var player = body.entity();
        var furnace = nearestWorkstation(player, Blocks.FURNACE, 16);
        if (furnace.isEmpty()) return placeItem(body, Items.FURNACE, "Place a furnace");
        if (player.getServerWorld().getBlockEntity(furnace.get()) instanceof AbstractFurnaceBlockEntity block && !block.getStack(0).isEmpty()) {
            return Optional.empty(); // Something is smelting already.
        }
        var inventory = player.getInventory();
        int input = -1, fuel = -1;
        for (int s = 0; s < 36; s++) {
            ItemStack stack = inventory.getStack(s);
            if (input < 0 && stack.isIn(ItemTags.LOGS_THAT_BURN)) input = s;
            if (fuel < 0 && stack.isIn(ItemTags.PLANKS)) fuel = s;
        }
        if (input < 0 || fuel < 0) return Optional.empty();
        var args = new LinkedHashMap<String, String>();
        args.put("action", "load");
        args.put("input_slot", String.valueOf(input));
        args.put("fuel_slot", String.valueOf(fuel));
        args.put("count", "1");
        return atWorkstation(player, furnace.get(), new Task("native_smelt", coords(furnace.get(), args), "Make charcoal: a log and a plank into the furnace"));
    }

    // ------------------------------------------------------------------ water, obsidian, flint

    private Optional<Task> fillWater(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var source = knowledge.nearest(world, WorldKnowledge.Kind.WATER, feet, p -> !blocked(p) && !unreachable(p) && !generatorCell(p)
                && p.getSquaredDistance(feet) < 64 * 64);
        if (source.isEmpty()) return Optional.empty();
        BlockPos water = source.get();
        if (eyeDistance(player, water) <= 4.0) {
            return Optional.of(new Task("native_bucket", coords(water, Map.of("mode", "fill")), "Fill a bucket at " + water.toShortString()));
        }
        // Water well overhead from underground is reached by digging up toward it, not by a walk that has no route.
        if (underground(player) && water.getY() > feet.getY() + 4) {
            Optional<Task> up = climb(body, "Climb toward water at " + water.toShortString());
            if (up.isPresent()) return up;
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
    /** Lava fills refused per source; the first refusal leads to a closer try, not to setting the source aside. */
    private final Map<Long, Integer> fillRefusals = new HashMap<>();
    /** Spots a cast was refused or failed from since the last one that worked; the next try is from somewhere else. */
    private final Set<Long> castFailedStands = new java.util.HashSet<>();

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
        // A source that was taken, or that could not be reached or filled from here (set aside), gives way to the next.
        if (castLava != null && (blocked(castLava) || !(world.getFluidState(castLava).isIn(FluidTags.LAVA) && world.getFluidState(castLava).isStill()))) castLava = null;
        if (castLava == null) castLava = knowledge.nearest(world, WorldKnowledge.Kind.LAVA, feet, p -> !blocked(p) && !generatorCell(p)).orElse(null);
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
            if (castFailedStands.contains(player.getBlockPos().asLong())) {
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
        KindredMod.LOGGER.info("{} gives up the frame site at {}: cannot clear {} ({})", player.getName().getString(),
                site.origin().toShortString(), cell.toShortString(), player.getServerWorld().getBlockState(cell));
        abandonedSites.add(siteKey(site));
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
        // The front row first, then the row behind it, from which the upper cells' faces show past the frame below.
        var stands = new java.util.ArrayList<BlockPos>();
        for (int a : new int[]{Math.clamp(across, 0, 3), 1, 2, 0, 3}) stands.add(site.front(a, 0));
        for (int a : new int[]{Math.clamp(across, 0, 3), 1, 2, 0, 3}) stands.add(site.front(a, 0).offset(site.toward().getOpposite()));
        for (BlockPos stand : stands) {
            if (stand.equals(player.getBlockPos()) || castFailedStands.contains(stand.asLong()) || !navigator.standable(stand) || blocked(stand)) continue;
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
                    Optional<PortalSite> site = portalSiteFrom(world, navigator, stand, s -> !abandonedSites.contains(siteKey(s)));
                    if (site.isPresent()) return site;
                }
            }
        }
        noSiteFrom = feet;
        return Optional.empty();
    }

    /** Sites given up because a cell could not be cleared; never chosen again in this run. */
    private final Set<Long> abandonedSites = new java.util.HashSet<>();

    private static long siteKey(PortalSite site) {
        return site.origin().asLong() * 4 + site.toward().getHorizontal();
    }

    /**
     * Lava within three blocks of the frame, the row in front of it or the stand: water poured while casting would run
     * into it and harden the pool, and the player works with its back to it. Players build away from lava too.
     */
    private static boolean lavaNear(ServerWorld world, PortalSite site) {
        BlockPos a = site.at(0, 0), b = site.at(3, 0), c = site.stand();
        int minX = Math.min(Math.min(a.getX(), b.getX()), c.getX()) - 3, maxX = Math.max(Math.max(a.getX(), b.getX()), c.getX()) + 3;
        int minZ = Math.min(Math.min(a.getZ(), b.getZ()), c.getZ()) - 3, maxZ = Math.max(Math.max(a.getZ(), b.getZ()), c.getZ()) + 3;
        for (BlockPos pos : BlockPos.iterate(minX, a.getY() - 2, minZ, maxX, a.getY() + 5, maxZ)) {
            if (world.getFluidState(pos).isIn(FluidTags.LAVA)) return true;
        }
        return false;
    }

    private static Optional<PortalSite> portalSiteFrom(ServerWorld world, NativeNavigator navigator, BlockPos stand,
                                                      Predicate<PortalSite> wanted) {
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
                PortalSite site = new PortalSite(origin.toImmutable(), along, facing, stand.toImmutable());
                if (ok && wanted.test(site) && !lavaNear(world, site)) return Optional.of(site);
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
            // A shield in the off hand stops the blast, so a shielded player takes a creeper on with less health to
            // spare, and faces one whose fuse is already burning close by rather than run, however hurt it is.
            boolean shield = player.getOffHandStack().isOf(Items.SHIELD);
            if (shield && creeper.getFuseSpeed() > 0 && player.distanceTo(creeper) < 6) return Optional.of(new Task("native_fight", strike.args(), strike.label()));
            float nerve = shield ? 6 : 10;
            if (strike.args().containsKey("slot") && player.getHealth() >= nerve) return Optional.of(new Task("native_fight", strike.args(), strike.label()));
            if (lastTick >= runFailedUntil) return Optional.of(runFrom(player, creeper));
            // Nowhere to run (a pit, a dead end): wall it off, and failing that, knock it back with whatever is in hand.
            Optional<Task> wall = cover(body, creeper);
            return wall.isPresent() ? wall : Optional.of(new Task("native_fight", strike.args(), strike.label()));
        }
        // Badly hurt with a mob on foot in reach that two swings will kill: finish it. Turning away from it is
        // worse: a zombie keeps pace with a player walking off and strikes its back (the tunnel fixture's zombie
        // killed a player with an iron sword that turned to back off at five health).
        // A foe the last swing knocked a few steps back is still the fight, as the running fight itself counts it.
        HostileEntity last = player.getHealth() < 6 ? finishable(player, foe != null && lastTick < foeUntil ? 5.0 : 3.0) : null;
        if (last != null) {
            foe = last.getUuid();
            foeUntil = lastTick + 100;
            Task strike = strike(player, last, "Fight " + Registries.ENTITY_TYPE.getId(last.getType()).getPath());
            return Optional.of(new Task("native_fight", strike.args(), strike.label()));
        }
        if (player.getHealth() < 6) return backOff(player).or(() -> coverFromHunters(body));
        // Outnumbered and already hurt: do not take them on one by one in the dark; get some distance first.
        if (player.getHealth() < 14 && hunters(player) >= 3) return backOff(player).or(() -> coverFromHunters(body));
        // No weapon in hand (just respawned, say): a fist fight with a zombie or a skeleton is lost, so keep away, as
        // a player with nothing does. Something that has closed right in is still struck below.
        if (!armed(player)) {
            boolean closeIn = threats(player).stream().anyMatch(mob -> player.squaredDistanceTo(mob) < 2.5 * 2.5);
            // Walking away from a skeleton stays in its line of fire; a block between them does not.
            Optional<Task> away = closeIn || shooter == null ? Optional.empty() : cover(body, shooter);
            if (away.isEmpty() && !closeIn) away = backOff(player);
            if (away.isPresent()) return away;
        }
        var threats = threats(player);
        HostileEntity nearest = threats.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
        if (nearest == null) nearest = rememberedFoe(player);
        if (nearest == null) return Optional.empty();
        // A skeleton that steps behind cover is still out there: keep after it for a few seconds.
        foe = nearest.getUuid();
        foeUntil = lastTick + 100;
        String name = Registries.ENTITY_TYPE.getId(nearest.getType()).getPath();
        // Underground in a passage, a mob on foot is met where the player stands, as players hold a tunnel mouth,
        // rather than chased out into the open cave; for ten seconds at most, in case it never comes.
        boolean passage = underground(player) && player.getBlockPos().equals(snug);
        if (passage && !(nearest instanceof net.minecraft.entity.ai.RangedAttackMob) && player.distanceTo(nearest) > 3.0) {
            if (holdingSince < 0) holdingSince = lastTick;
            if (lastTick - holdingSince < 200) {
                return Optional.of(new Task("native_wait", Map.of("seconds", "1"), "Hold the passage against " + name));
            }
        } else {
            holdingSince = -1;
        }
        Task strike = strike(player, nearest, "Fight " + name);
        return Optional.of(new Task("native_fight", strike.args(), strike.label()));
    }

    private static int hunters(ServerPlayerEntity player) {
        return player.getServerWorld().getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(12, 6, 12),
                mob -> mob.isAlive() && mob.getTarget() == player).size();
    }

    /** Too hurt to fight: put distance between the player and whatever is after it. */
    private Optional<Task> backOff(ServerPlayerEntity player) {
        // A way off that just failed is not tried again at once: in live run 59 it was tried a dozen times in a row
        // while a skeleton went on shooting, and cover came too late.
        if (lastTick < backOffFailedUntil) return Optional.empty();
        var world = player.getServerWorld();
        var hunters = world.getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(16, 6, 16),
                mob -> mob.isAlive() && mob.getTarget() == player);
        if (hunters.isEmpty()) return Optional.empty();
        Vec3d away = Vec3d.ZERO;
        for (HostileEntity mob : hunters) away = away.add(player.getPos().subtract(mob.getPos()).normalize());
        if (away.lengthSquared() < 1.0E-4) return Optional.empty();
        String name = Registries.ENTITY_TYPE.getId(hunters.getFirst().getType()).getPath();
        // Back the way it came first, ground it knows it can walk: straight away from the mobs led live run 59 down
        // into a dark cave beyond its staircase, onto a creeper.
        Optional<BlockPos> back = trailAwayFrom(hunters);
        if (back.isPresent()) {
            return Optional.of(new Task("native_goto", coords(back.get(), Map.of("range", "1", "partial", "true", "sprint", "true")),
                    "Back off from " + name + " the way it came"));
        }
        away = away.normalize().multiply(10);
        BlockPos target = BlockPos.ofFloored(player.getX() + away.x, player.getY(), player.getZ() + away.z);
        // At a sprint: walking, a zombie keeps pace and strikes the player's back all the way.
        return Optional.of(new Task("native_goto", coords(target, Map.of("range", "3", "partial", "true", "sprint", "true")), "Back off from " + name));
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
    /** A block between the player and the nearest mob after it, when there is nowhere to back off to. */
    private Optional<Task> coverFromHunters(NativePlayerBody body) {
        var player = body.entity();
        return player.getServerWorld().getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(16, 6, 16),
                        mob -> mob.isAlive() && mob.getTarget() == player)
                .stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).flatMap(mob -> cover(body, mob));
    }

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
        var open = openings(player.getServerWorld(), player.getBlockPos());
        if (open.isEmpty() || open.size() > 4) return Optional.empty();
        for (BlockPos cell : open) {
            Optional<Task> place = placeBlock(body, cell, "Seal the shelter at " + cell.toShortString());
            if (place.isPresent()) return place;
        }
        return Optional.empty();
    }

    /** Below ground, or on a sky island down in a pit with ground on every side at feet and head. */
    private static boolean sheltered(ServerPlayerEntity player) {
        if (underground(player)) return true;
        if (groundBelow(player)) return false;
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (passable(world, feet.offset(direction)) || passable(world, feet.up().offset(direction))) return false;
        }
        return true;
    }

    /** In a hole it cannot step out of: the ground two or more above its feet on at least three sides. */
    private static boolean inPit(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        int walls = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos side = feet.offset(direction);
            if (world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, side.getX(), side.getZ()) >= feet.getY() + 2) walls++;
        }
        return walls >= 3;
    }

    /** Over the void, the island's edge is never dug: its wall and outer blocks are all that keep the player on it. */
    private boolean islandEdge(ServerPlayerEntity player, BlockPos cell) {
        return !groundBelow(player) && besideTheVoid(player.getServerWorld(), cell);
    }

    /** Open cells beside the player's feet and head, and above its head: the ways in to where it stands. */
    private static List<BlockPos> openings(ServerWorld world, BlockPos feet) {
        var open = new java.util.ArrayList<BlockPos>();
        for (Direction direction : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            for (BlockPos cell : List.of(feet.offset(direction), feet.up().offset(direction))) if (opening(world, cell)) open.add(cell);
        }
        if (opening(world, feet.up(2))) open.add(feet.up(2));
        return open;
    }

    // ------------------------------------------------------------------ cobblestone generator

    /**
     * A cobblestone generator dug into the ground, as sky-island players build one: a one-wide trench with lava at
     * one end and water at the other, the water falling into a hole dug next to it, so lava flowing down the trench
     * meets the falling water and hardens into cobblestone in the same cell each time that cell is mined. The
     * player mines it from a pocket dug beside that cell, low enough to pick up what drops. It digs and pours from
     * the ground beside the trench: the water end from beside the water, the rest from beyond the pocket, and the
     * lava from beside the lava.
     */
    private record Generator(BlockPos lava, BlockPos flow, BlockPos cobble, BlockPos hole, BlockPos water, BlockPos stand, BlockPos pourFrom) {
        /** Cells dug out, in the order they are dug: the water end first, so nothing is dug beside poured lava. */
        List<BlockPos> dug() { return List.of(water, hole, hole.down(), cobble, flow, lava, stand); }

        Direction along() { return Direction.fromVector(flow.getX() - lava.getX(), 0, flow.getZ() - lava.getZ()); }

        Direction side() { return Direction.fromVector(stand.getX() - cobble.getX(), 0, stand.getZ() - cobble.getZ()); }

        /** Where the player stands on the ground beside the trench to dig or pour into {@code cell}. */
        BlockPos from(BlockPos cell) {
            if (cell.equals(water) || cell.equals(hole) || cell.equals(hole.down())) return water.offset(side()).up();
            if (cell.equals(lava)) return lava.offset(side()).up();
            return pourFrom;
        }

        /** Other places on the ground around the trench to try digging from, if the first gives no view of a cell. */
        List<BlockPos> stands(BlockPos cell) {
            Direction far = side().getOpposite();
            return List.of(from(cell), pourFrom, water.offset(side()).up(), hole.offset(far).up(), water.offset(far).up(),
                    cobble.offset(far).up(), water.offset(along()).up(), lava.offset(side()).up());
        }

        /** The floors and walls that keep the lava and water in, and the stand's own walls. */
        List<BlockPos> walls() {
            Direction along = along(), side = side();
            var walls = new java.util.ArrayList<BlockPos>(List.of(lava.down(), flow.down(), cobble.down(), water.down(), stand.down(), hole.down(2),
                    lava.offset(along.getOpposite()), water.offset(along), stand.offset(side), cobble.offset(side.getOpposite())));
            for (BlockPos cell : List.of(lava, flow, hole, water, hole.down())) {
                walls.add(cell.offset(side));
                walls.add(cell.offset(side.getOpposite()));
            }
            return walls;
        }
    }

    /** The generator this player built and mines, and the one it is building. */
    private Generator generator, generatorPlan;
    /** Cells of the generator being built paired with places they could not be dug from. */
    private final Set<Long> generatorTried = new HashSet<>();

    /** Within fire range of a generator's lava: no wooden workstation goes there. */
    private boolean nearGeneratorLava(BlockPos pos) {
        for (Generator g : java.util.Arrays.asList(generator, generatorPlan)) {
            if (g == null) continue;
            for (BlockPos near : burnZone(g)) if (near.equals(pos)) return true;
        }
        return false;
    }

    /** One of the cells of the generator built or being built, which no other work takes fluid from or digs into. */
    private boolean generatorCell(BlockPos pos) {
        for (Generator g : java.util.Arrays.asList(generator, generatorPlan)) {
            if (g != null && (g.dug().contains(pos) || pos.equals(g.pourFrom()))) return true;
        }
        return false;
    }

    private Optional<Task> buildGenerator(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        if (generatorPlan != null && generatorPlan.dug().stream().anyMatch(this::blocked)) generatorPlan = null; // A dig there failed.
        if (generatorPlan == null) generatorPlan = generatorSite(player).orElse(null);
        Generator g = generatorPlan;
        if (g == null) return Optional.empty();
        boolean watered = still(world, g.water(), FluidTags.WATER), lit = still(world, g.lava(), FluidTags.LAVA);
        if (watered && lit) {
            generator = g;
            generatorPlan = null;
            // Nothing else digs into it, carves room for a workstation out of its walls or jumps into its hole.
            for (BlockPos cell : g.dug()) blockedUntil.put(cell.asLong(), Long.MAX_VALUE);
            for (BlockPos cell : g.walls()) blockedUntil.put(cell.asLong(), Long.MAX_VALUE);
            return mineGenerator(body);
        }
        if (!watered) {
            for (BlockPos cell : g.dug()) {
                if (world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) continue;
                if (breakableNow(body, cell) && !feet.down().equals(cell)) return Optional.of(clearAt(cell, "Generator: dig " + cell.toShortString()));
                // No view of it from here: the next place around the trench, as a player steps round for a better
                // angle; with none left, the site is given up for another.
                generatorTried.add(cell.asLong() * 31 + feet.asLong());
                for (BlockPos stand : g.stands(cell)) {
                    if (generatorTried.contains(cell.asLong() * 31 + stand.asLong()) || !standable(world, stand)) continue;
                    return Optional.of(go(stand, 0.4, false, "Generator: stand by the trench"));
                }
                for (BlockPos dug : g.dug()) blockedUntil.merge(dug.asLong(), lastTick + 6000, Math::max);
                generatorPlan = null;
                generatorTried.clear();
                return Optional.empty();
            }
            if (ItemMoves.count(player.getInventory(), Items.WATER_BUCKET) == 0) return Optional.empty();
            if (!feet.equals(g.from(g.water()))) return Optional.of(go(g.from(g.water()), 0.4, false, "Generator: stand by the water end"));
            return Optional.of(new Task("native_bucket", coords(g.water(), Map.of("mode", "pour")), "Generator: pour water"));
        }
        // Grass and flowers near the lava would catch fire and spread it: cleared first.
        for (BlockPos near : burnZone(g)) {
            var state = world.getBlockState(near);
            if (state.isBurnable() && state.isReplaceable() && breakableNow(body, near)) return Optional.of(clearAt(near, "Generator: clear " + near.toShortString()));
        }
        if (ItemMoves.count(player.getInventory(), Items.LAVA_BUCKET) == 0) return Optional.empty();
        if (!feet.equals(g.from(g.lava()))) return Optional.of(go(g.from(g.lava()), 0.4, false, "Generator: stand by the lava end"));
        return Optional.of(new Task("native_bucket", coords(g.lava(), Map.of("mode", "pour_lava")), "Generator: pour lava"));
    }

    /** Mine the generator's cobblestone from its stand; a generator whose lava or water is gone is forgotten. */
    private Optional<Task> mineGenerator(NativePlayerBody body) {
        if (generator == null) return Optional.empty();
        var player = body.entity();
        var world = player.getServerWorld();
        Generator g = generator;
        if (!still(world, g.lava(), FluidTags.LAVA) || !still(world, g.water(), FluidTags.WATER)) {
            generator = null;
            return Optional.empty();
        }
        if (!player.getBlockPos().equals(g.stand())) return Optional.of(go(g.stand(), 0.3, false, "Generator: step into its stand"));
        var state = world.getBlockState(g.cobble());
        // Mined beside the flowing lava: the lava that runs into the gap meets the water and hardens again.
        if (state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.STONE)) {
            return Optional.of(new Task("native_break", coords(g.cobble(), Map.of("guarded", "true")), "Generator: mine cobblestone"));
        }
        return Optional.of(new Task("native_wait", Map.of("seconds", "1"), "Generator: wait for the cobblestone"));
    }

    private Optional<Task> fillLava(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var source = knowledge.nearest(world, WorldKnowledge.Kind.LAVA, feet, p -> !blocked(p) && !unreachable(p) && !generatorCell(p)
                && still(world, p, FluidTags.LAVA) && p.getSquaredDistance(feet) < 64 * 64);
        if (source.isEmpty()) return Optional.empty();
        BlockPos lava = source.get();
        if (eyeDistance(player, lava) <= 4.0) {
            return Optional.of(new Task("native_bucket", coords(lava, Map.of("mode", "fill")), "Fill a bucket with lava at " + lava.toShortString()));
        }
        return Optional.of(go(lava, 2.5, false, "Walk to lava at " + lava.toShortString()));
    }

    /**
     * The nearest place within six blocks where a generator fits, in the layer the player stands on or, standing in a
     * step cut one below the ground (as on the way out of a night's pit), the layer at its feet.
     */
    private Optional<Generator> generatorSite(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        Generator best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int layer = -1; layer <= 0; layer++) for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            BlockPos origin = feet.add(dx, layer, dz);
            for (Direction along : Direction.Type.HORIZONTAL) {
                for (Direction side : List.of(along.rotateYClockwise(), along.rotateYCounterclockwise())) {
                    Generator g = generatorAt(world, origin, along, side);
                    if (g == null || g.dug().contains(feet.down()) || g.dug().stream().anyMatch(this::blocked)) continue;
                    double distance = g.pourFrom().getSquaredDistance(feet);
                    if (distance < bestDistance) {
                        best = g;
                        bestDistance = distance;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A generator laid out from {@code origin} along {@code along}, its stand on {@code side}, or null when the
     * ground there will not hold one: every cell to dig must be plain diggable ground, open above; the floors and
     * walls that keep the lava and water in must be solid; and nothing but grass that can be cleared may burn
     * within reach of the lava.
     */
    private static Generator generatorAt(ServerWorld world, BlockPos origin, Direction along, Direction side) {
        BlockPos lava = origin, flow = lava.offset(along), cobble = flow.offset(along), hole = cobble.offset(along), water = hole.offset(along);
        BlockPos stand = cobble.offset(side);
        var g = new Generator(lava, flow, cobble, hole, water, stand, stand.offset(side).up());
        for (BlockPos cell : g.dug()) if (!diggable(world, cell)) return null;
        for (BlockPos cell : List.of(lava, flow, cobble, hole, water, stand)) if (!world.getBlockState(cell.up()).isAir()) return null;
        for (BlockPos from : List.of(g.pourFrom(), g.from(water), g.from(lava))) {
            if (!world.getBlockState(from).isAir() || !world.getBlockState(from.up()).isAir()) return null;
        }
        for (BlockPos cell : g.walls()) if (!sturdy(world, cell)) return null;
        for (BlockPos near : burnZone(g)) {
            var state = world.getBlockState(near);
            if (state.isBurnable() && !state.isReplaceable()) return null;
        }
        return g;
    }

    /** Where lava in the trench could set fire: three blocks around its two lava cells, from their level up. */
    private static Iterable<BlockPos> burnZone(Generator g) {
        BlockPos a = g.lava(), b = g.flow();
        return BlockPos.iterate(Math.min(a.getX(), b.getX()) - 3, a.getY(), Math.min(a.getZ(), b.getZ()) - 3,
                Math.max(a.getX(), b.getX()) + 3, a.getY() + 3, Math.max(a.getZ(), b.getZ()) + 3);
    }

    /** Room for the player to stand: solid ground under two open cells, no liquid. */
    private static boolean standable(ServerWorld world, BlockPos pos) {
        return sturdy(world, pos.down()) && world.getBlockState(pos).isAir() && world.getBlockState(pos.up()).isAir();
    }

    private static boolean sturdy(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.isFullCube(world, pos) && state.getFluidState().isEmpty() && !(state.getBlock() instanceof net.minecraft.block.FallingBlock);
    }

    /** Plain ground a player digs by hand or with a pickaxe: dirt, grass, stone, nothing with contents. */
    private static boolean diggable(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        float hardness = state.getHardness(world, pos);
        return sturdy(world, pos) && hardness >= 0 && hardness <= 2.0f && !state.hasBlockEntity();
    }

    private static boolean still(ServerWorld world, BlockPos pos, net.minecraft.registry.tag.TagKey<net.minecraft.fluid.Fluid> fluid) {
        var state = world.getFluidState(pos);
        return state.isIn(fluid) && state.isStill();
    }

    // ------------------------------------------------------------------ replanting

    /** Saplings this player planted, until each grows into a tree or is gone. */
    private final Set<Long> planted = new HashSet<>();

    /** The nearest sapling this player planted that is still a sapling; grown or lost ones are forgotten. */
    private Optional<BlockPos> nearestSapling(ServerPlayerEntity player) {
        var world = player.getServerWorld();
        planted.removeIf(key -> {
            BlockPos pos = BlockPos.fromLong(key);
            return world.isChunkLoaded(pos) && !world.getBlockState(pos).isIn(BlockTags.SAPLINGS);
        });
        BlockPos feet = player.getBlockPos();
        return planted.stream().map(BlockPos::fromLong).min(java.util.Comparator.comparingDouble(pos -> pos.getSquaredDistance(feet)));
    }

    /**
     * Plant a carried sapling on dirt or grass open to the sky, with room above for a trunk, two blocks clear of
     * other saplings and trunks, and out of reach of a generator's lava.
     */
    private Optional<Task> plantSapling(NativePlayerBody body) {
        var player = body.entity();
        var inventory = player.getInventory();
        int slot = -1;
        for (int s = 0; s < 36 && slot < 0; s++) if (inventory.getStack(s).isIn(ItemTags.SAPLINGS)) slot = s;
        if (slot < 0) return Optional.empty();
        if (slot >= 9) return Optional.of(swapToHotbar(body, slot, "Ready a sapling"));
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos ground : BlockPos.iterate(feet.add(-6, -3, -6), feet.add(6, 2, 6))) {
            BlockPos spot = ground.up();
            if (!world.getBlockState(ground).isIn(BlockTags.DIRT) || !world.getBlockState(spot).isAir() || !world.isSkyVisible(spot)) continue;
            if (spot.equals(feet) || spot.equals(feet.up()) || blocked(spot) || generatorCell(ground) || nearGeneratorLava(spot)) continue;
            boolean room = true;
            for (int h = 1; h <= 5 && room; h++) room = world.getBlockState(spot.up(h)).isAir();
            if (!room) continue;
            boolean crowded = false;
            for (BlockPos near : BlockPos.iterate(spot.add(-2, 0, -2), spot.add(2, 1, 2))) {
                var state = world.getBlockState(near);
                if (state.isIn(BlockTags.SAPLINGS) || state.isIn(BlockTags.LOGS)) { crowded = true; break; }
            }
            if (crowded) continue;
            double distance = spot.getSquaredDistance(feet);
            if (distance < bestDistance) {
                best = spot.toImmutable();
                bestDistance = distance;
            }
        }
        if (best == null) return Optional.empty();
        if (eyeDistance(player, best) > 4.4) return Optional.of(go(best, 2.5, true, "Walk to a place to plant"));
        return Optional.of(new Task("native_place", coords(best, Map.of("side", "up", "slot", String.valueOf(slot))), "Plant a sapling at " + best.toShortString()));
    }

    /** Stay by the nearest planted sapling until it grows; it needs daylight, so this waits out the night too. */
    private Optional<Task> awaitTree(NativePlayerBody body) {
        var player = body.entity();
        var sapling = nearestSapling(player);
        if (sapling.isEmpty()) return Optional.empty();
        if (player.getBlockPos().getSquaredDistance(sapling.get()) > 6 * 6) return Optional.of(go(sapling.get(), 4, true, "Walk back to the saplings"));
        return Optional.of(new Task("native_wait", Map.of("seconds", "4"), "Wait for the sapling at " + sapling.get().toShortString() + " to grow"));
    }

    /** Break the nearest leaves in reach for the saplings they drop, picking the drops up. */
    private Optional<Task> gatherSaplings(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        var leaves = knowledge.nearest(world, WorldKnowledge.Kind.LEAVES, feet, p -> !blocked(p) && !unreachable(p)
                && world.getBlockState(p).isIn(BlockTags.LEAVES) && p.getSquaredDistance(feet) <= PortalRunPlanner.ISLAND_REACH * PortalRunPlanner.ISLAND_REACH);
        if (leaves.isEmpty()) return Optional.empty();
        BlockPos leaf = leaves.get();
        if (breakableNow(body, leaf)) return Optional.of(breakAt(leaf, "Break leaves for saplings at " + leaf.toShortString()));
        if (Math.hypot(leaf.getX() - feet.getX(), leaf.getZ() - feet.getZ()) <= 2) {
            blockedUntil.merge(leaf.asLong(), lastTick + 1200, Math::max); // Overhead and out of reach from here.
            return Optional.empty();
        }
        return Optional.of(go(leaf, 2.0, true, "Walk to the leaves at " + leaf.toShortString()));
    }

    // ------------------------------------------------------------------ holing up

    /** The last spot underground with few ways in (a staircase step, a tunnel), where the player can wall itself in. */
    private BlockPos snug;
    /** Since when the player has been walled in to recover; negative when it is not. */
    private long holedUpSince = -1;
    /** Until when a fight, or a retreat (backing off, cover, holing up), just chosen is held. */
    private long fightHeldUntil = -1, retreatHeldUntil = -1;
    private static final int FIGHT_HOLD = 60, RETREAT_HOLD = 100;
    /** Since when it has been waiting in a passage for a mob to come to it; negative when it is not. */
    private long holdingSince = -1;

    /**
     * Underground and outmatched for its gear (a creeper or a shooter without a shield, several mobs after it, low
     * health), the player does what players do when a dig breaks into a busy cave: it walls off the ways in where it
     * stands if that is a passage, or backs into the staircase it came down and walls itself in there. Then it waits
     * behind its blocks until healed, with the cave marked dangerous for a few minutes and the wall left standing,
     * so the dig goes on somewhere else.
     */
    private Optional<Task> holeUp(NativePlayerBody body) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        if (!underground(player)) {
            holedUpSince = -1;
            return Optional.empty();
        }
        var open = openings(world, feet);
        // A mob on foot already within reach is past any wall: fight it where the player stands. So is one it is
        // already fighting that a hit has only knocked a step or two back: it comes straight back.
        boolean pressed = player.hurtTime > 0 || world.getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(4, 1.5, 4),
                mob -> mob.isAlive() && mob.getTarget() == player && !(mob instanceof CreeperEntity)
                        && (player.squaredDistanceTo(mob) < 2.5 * 2.5 || mob.getUuid().equals(foe) && lastTick <= foeUntil)).size() > 0;
        net.minecraft.entity.LivingEntity danger = outmatchedBy(player);
        if (danger != null && pressed && !(danger instanceof CreeperEntity)) return Optional.empty();
        if (danger != null) {
            markDanger(danger.getBlockPos(), lastTick + CAVE_DANGER_TICKS);
            if (holedUpSince < 0) holedUpSince = lastTick;
            if (open.size() <= 4) {
                open.sort(java.util.Comparator.comparingDouble(cell -> cell.getSquaredDistance(danger.getBlockPos())));
                for (BlockPos cell : open) {
                    if (occupied(world, cell)) continue;
                    Optional<Task> place = placeBlock(body, cell, "Hole up: seal " + cell.toShortString());
                    if (place.isPresent()) return place;
                }
                if (open.isEmpty()) return Optional.of(new Task("native_wait", Map.of("seconds", "2"), "Hole up: wait behind the wall"));
            }
            if (snug != null && !snug.equals(feet) && snug.getSquaredDistance(feet) <= 16 * 16 && !blocked(snug)) {
                return Optional.of(go(snug, 0.3, true, "Hole up: back into the passage"));
            }
            return Optional.empty(); // Nowhere to hole up: fight, run or back off as before.
        }
        // A look before going in: a cave that shows mobs the player is no match for yet is walled off from the
        // passage it opened into, before any of them comes for it, and left alone for a while.
        HostileEntity peril = pressed ? null : peril(player);
        if (peril != null) {
            markDanger(peril.getBlockPos(), lastTick + CAVE_DANGER_TICKS);
            if (open.size() <= 4) {
                Vec3d toward = peril.getPos().subtract(Vec3d.ofBottomCenter(feet));
                for (BlockPos cell : open) {
                    if (Vec3d.ofCenter(cell).subtract(Vec3d.ofCenter(feet)).dotProduct(toward) <= 0 || occupied(world, cell)) continue; // The way back stays open.
                    Optional<Task> place = placeBlock(body, cell, "Hole up: seal " + cell.toShortString());
                    if (place.isPresent()) return place;
                }
            }
        }
        if (player.isOnGround() && open.size() <= 3 && world.getFluidState(feet).isEmpty()) snug = feet.toImmutable();
        if (holedUpSince >= 0) {
            if (pressed) return Optional.empty(); // Something got in: fight back rather than wait.
            if (player.getHealth() >= 18 || lastTick - holedUpSince > 2400) {
                holedUpSince = -1;
                return Optional.empty();
            }
            if (open.size() <= 4) {
                for (BlockPos cell : open) {
                    if (occupied(world, cell)) continue;
                    Optional<Task> place = placeBlock(body, cell, "Hole up: seal " + cell.toShortString());
                    if (place.isPresent()) return place;
                }
            }
            return Optional.of(new Task("native_wait", Map.of("seconds", "4"), "Hole up: recover behind the wall"));
        }
        return Optional.empty();
    }

    /** The mob the player is no match for down here with what it has, or null. */
    private net.minecraft.entity.LivingEntity outmatchedBy(ServerPlayerEntity player) {
        boolean shield = player.getOffHandStack().isOf(Items.SHIELD);
        CreeperEntity creeper = creeperAfter(player);
        if (creeper != null && !shield) return creeper;
        // A provoked enderman out-hits anything short of an iron sword, and cannot follow into a two-high passage.
        var enderman = player.getServerWorld().getEntitiesByClass(EndermanEntity.class, player.getBoundingBox().expand(16, 6, 16),
                mob -> mob.isAlive() && mob.getTarget() == player).stream().findFirst();
        if (enderman.isPresent() && swordTier(player) < 3) return enderman.get();
        var hunters = player.getServerWorld().getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(12, 6, 12),
                mob -> mob.isAlive() && mob.getTarget() == player);
        if (hunters.isEmpty()) return null;
        HostileEntity shooter = shooter(player);
        if (shooter != null && !shield && (hunters.size() >= 2 || player.getHealth() < 16)) return shooter;
        // Advised to play safe: holed up four health sooner.
        float careful = stance() == StrategyAdvice.Stance.PLAY_SAFE ? 4 : 0;
        if (hunters.size() >= 2 && !shield || player.getHealth() < (shield ? 8 : 12) + careful) {
            return hunters.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
        }
        return null;
    }

    /**
     * Mobs in view down here that the player is no match for yet, though none has come for it: a shooter or a
     * creeper, or two mobs at once, without a shield; three at once with one. The nearest of them, or null.
     */
    private static HostileEntity peril(ServerPlayerEntity player) {
        boolean shield = player.getOffHandStack().isOf(Items.SHIELD);
        var seen = player.getServerWorld().getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(16, 8, 16),
                mob -> mob.isAlive() && !(mob instanceof EndermanEntity) && player.canSee(mob));
        boolean many = seen.size() >= (shield ? 3 : 2);
        boolean deadly = !shield && seen.stream().anyMatch(mob -> mob instanceof net.minecraft.entity.ai.RangedAttackMob || mob instanceof CreeperEntity);
        if (!many && !deadly) return null;
        return seen.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    private static boolean opening(ServerWorld world, BlockPos cell) {
        return passable(world, cell) && world.getFluidState(cell).isEmpty() && world.getBlockState(cell).isReplaceable();
    }

    /** An opening with a mob standing in it cannot be sealed: the block would not go in (live run 55 tried until it died). */
    private static boolean occupied(ServerWorld world, BlockPos cell) {
        return !world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, new Box(cell), net.minecraft.entity.LivingEntity::isAlive).isEmpty();
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
                || running.label().startsWith("Take cover") || running.label().startsWith("Hole up")
                || running.label().startsWith("Hold the passage")) return null;
        if (running.label().startsWith("Fight") || running.label().startsWith("Close in")) {
            // Worn down mid-fight: break it off, as a player does, so backing off or taking cover can take over.
            // The same line below which the goal never starts a fight. Not a creeper faced behind a shield, though:
            // running from its blast is worse than taking it on the shield.
            boolean shielded = running.label().equals("Fight creeper") && player.getOffHandStack().isOf(Items.SHIELD);
            // A mob a hit has just knocked a few steps back still counts as one in reach.
            return player.getHealth() < 6 && !shielded && finishable(player, 5.0) == null ? "Badly hurt; break off the fight" : null;
        }
        return !threats(player).isEmpty() || player.getHealth() >= 6 && creeperAfter(player) != null
                ? "A hostile mob is attacking; fight back first" : null;
    }

    /**
     * The goal's own combat: while a goal runs it is the one controller of a fight, so these outrank the runtime's
     * tactical strikes and one-block retreats, which used to cut in between its steps (live runs flipped between
     * fighting, backing off and holing up every second before most deaths).
     */
    static boolean combatTask(Task task) {
        if (task == null) return false;
        String label = task.label();
        return label.startsWith("Fight") || label.startsWith("Close in") || label.startsWith("Back off") || label.startsWith("Take cover")
                || label.startsWith("Run from") || label.startsWith("Hold the passage");
    }

    /** Goal work that outranks everything else: a creeper's escape, and digging out of a block the head is stuck in. */
    static boolean urgentTask(Task task) {
        return creeperTask(task) || task != null && (task.label().startsWith("Dig out of") || task.label().startsWith("Hole up"));
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

    /** No hostile mob within earshot (sixteen blocks) of {@code pos}; endermen keep to themselves unless provoked. */
    private static boolean quietAround(ServerWorld world, BlockPos pos) {
        return world.getEntitiesByClass(HostileEntity.class, new Box(pos).expand(16), mob -> mob.isAlive() && !(mob instanceof EndermanEntity)).isEmpty();
    }

    /** A shield in the off hand or carried. */
    private static boolean shielded(ServerPlayerEntity player) {
        return player.getOffHandStack().isOf(Items.SHIELD) || ItemMoves.count(player.getInventory(), Items.SHIELD) > 0;
    }

    /** The best sword carried, on the tier scale (0 none, 2 stone, 3 iron). */
    private static int swordTier(ServerPlayerEntity player) {
        int best = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isIn(ItemTags.SWORDS)) best = Math.max(best, tier(stack.getItem()));
        }
        return best;
    }

    /** Damage of one full swing with the best sword on the tier scale (a fist with none). */
    private static float swordDamage(int tier) {
        return switch (tier) { case 0 -> 1; case 1 -> 4; case 2 -> 5; case 3 -> 6; case 4 -> 7; default -> 8; };
    }

    /** The nearest mob on foot after the player, within reach, that two full swings of its best sword will kill. */
    private static HostileEntity finishable(ServerPlayerEntity player) {
        return finishable(player, 3.0);
    }

    private static HostileEntity finishable(ServerPlayerEntity player, double reach) {
        if (!armed(player)) return null;
        float damage = swordDamage(swordTier(player)) * 0.9f; // Allowing for a little armour.
        return player.getServerWorld().getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(reach, 1.5, reach),
                        mob -> mob.isAlive() && mob.getTarget() == player && !(mob instanceof CreeperEntity)
                                && !(mob instanceof net.minecraft.entity.ai.RangedAttackMob) && player.distanceTo(mob) < reach
                                && player.canSee(mob) && mob.getHealth() <= 2 * damage)
                .stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    /** A sword or an axe in the hotbar, as {@link #strike} would use. */
    private static boolean armed(ServerPlayerEntity player) {
        for (int s = 0; s < 9; s++) {
            var stack = player.getInventory().getStack(s);
            if (stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES)) return true;
        }
        return false;
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

    /**
     * Drops worth walking back for: route items a break left lying (say carried off by water), and its own
     * workstations (a table left behind cost run 57 its way on).
     */
    private static final Set<Item> KEEP = Set.of(Items.OBSIDIAN, Items.DIAMOND, Items.RAW_IRON, Items.FLINT, Items.CRAFTING_TABLE, Items.FURNACE);

    /**
     * A drop worth walking back for. Where trees grow only from what is planted, a sapling too: those a cut tree's
     * leaves drop as they decay are the island's only wood to come (sky-island run 2 cut its one tree, picked up none
     * and was left with no wood at all).
     */
    private static boolean keep(ItemStack stack, boolean saplings) {
        return KEEP.contains(stack.getItem()) || saplings && stack.isIn(ItemTags.SAPLINGS);
    }

    private Optional<Task> pickUpLoose(ServerPlayerEntity player) {
        // A drop in flowing water, such as the water poured for obsidian, waits until the water is taken back.
        var world = player.getServerWorld();
        boolean saplings = !groundBelow(player);
        var drops = world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(8, 3, 8),
                drop -> keep(drop.getStack(), saplings) && player.canSee(drop) && !blocked(drop.getBlockPos())
                        && (world.getFluidState(drop.getBlockPos()).isEmpty() || world.getFluidState(drop.getBlockPos()).isStill()));
        if (drops.isEmpty()) {
            // One still in the water, close by: a moment for the water to drain off it rather than walk away from it (a
            // fixture's obsidian was left behind once caving without a shield was allowed). Five seconds at most each,
            // and not while its own poured water is still to be taken back.
            if (obsidianSite != null) return Optional.empty();
            if (dropWaits.size() > 256) dropWaits.clear();
            var settling = world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(5, 3, 5),
                    drop -> keep(drop.getStack(), saplings) && !blocked(drop.getBlockPos()));
            for (ItemEntity drop : settling) {
                long since = dropWaits.computeIfAbsent(drop.getUuid(), id -> lastTick);
                if (lastTick - since < 100) return Optional.of(new Task("native_wait", Map.of("seconds", "1"), "Wait for the drop to settle"));
            }
            return Optional.empty();
        }
        ItemEntity nearest = drops.stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElseThrow();
        return Optional.of(go(nearest.getBlockPos(), 0.6, false, "Pick up " + Registries.ITEM.getId(nearest.getStack().getItem()).getPath().replace('_', ' ')));
    }

    /** When the player started waiting for each drop still in water. */
    private final Map<java.util.UUID, Long> dropWaits = new HashMap<>();

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
