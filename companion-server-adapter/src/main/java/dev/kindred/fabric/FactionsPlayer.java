package dev.kindred.fabric;

import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.Territory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: one AI player's side of a faction world. It remembers whose land it has seen, decides now and then what
 * the faction world asks of it ({@link FactionPlay}), and remembers where it is going to claim, scout or defend.
 *
 * Technical: The adapter's per-player state for M-06i, on the server thread. Sightings are the claimed chunks it stood
 * in or beside (the eight round its own), with when; they are the only enemy land {@link FactionPlay} may choose from,
 * kept to the last {@value #SEEN_MAX} and not kept across a restart. Its spare stock is deliberately narrow: the common
 * building blocks below beyond {@value #KEEP} of each, never tools, food, ores or anything it might need; its wants are
 * none yet (nothing in its plans names a purchase), so it neither buys nor bids until they do.
 */
final class FactionsPlayer {
    /** Once every 30 seconds it weighs the faction world again. */
    static final int DECIDE_TICKS = 600;
    static final int SEEN_MAX = 512;
    /** Of each common block it keeps this many for its own building. */
    static final int KEEP = 64;
    /** How far inside a chunk a walk to it ends, and within how much of that spot. */
    static final int INSET = 3;
    static final String RANGE = "1";
    /** The longest single walk toward a far chunk. */
    static final int LEG = 48;
    static final Set<String> COMMON = Set.of("minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:gravel",
            "minecraft:netherrack", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:sand");

    private final Map<String, FactionPlay.Sighting> seen = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, FactionPlay.Sighting> eldest) { return size() > SEEN_MAX; }
    };
    long decidedAt = Long.MIN_VALUE / 2;
    /** When its faction walk first yielded to the goal's dig-in this night (F-35h2b); {@code Long.MIN_VALUE} by day. */
    long nightYieldAt = Long.MIN_VALUE;
    /** Where it is going, and why: a claim beside its land, a seen border to scout, or home. */
    FactionPlay.Choice move;
    /** What it chose to ask for, waiting for its turn to act. */
    FactionPlay.Choice chosen;
    /** A walk's target refused (no route) this many times in a row, and until when it is not asked again. */
    record Refusals(int times, long until) { }
    private final Map<String, Refusals> refused = new LinkedHashMap<>();
    /** After this many refusals in a row a target is set aside, and the faction world's choice is made without it. */
    static final int SET_ASIDE = 3;
    /** The wait after the first refusal, in ticks; it doubles with each, to a game day at most. */
    static final long BACKOFF = 200, BACKOFF_MAX = 24_000;

    /** A walk toward {@code target} refused: not asked again for a while, longer each time. */
    void refused(String target, long tick) {
        refused(target, tick, null, true);
    }

    /**
     * A walk toward {@code target} refused, from {@code at}, at the surface or not: the target waits as above, and is
     * set aside after {@value #SET_ASIDE}. Once {@value #STREAK} places have been set aside so with no walk between,
     * whatever the places, no faction walk is asked for a while, and after that wait each further refusal with nothing
     * between waits again, twice as long. Each place still gets its own three tries first.
     */
    void refused(String target, long tick, net.minecraft.util.math.BlockPos at, boolean atSurface) {
        refusalsCounted++;
        var before = refused.get(target);
        int times = before == null ? 1 : before.times() + 1;
        refused.put(target, new Refusals(times, tick + Math.min(BACKOFF_MAX, BACKOFF << Math.min(times - 1, 10))));
        if (streakFrom == null && at != null) {
            streakFrom = at.toImmutable();
            streakAtSurface = atSurface;
        }
        if (times == SET_ASIDE) streak++;
        streakPause(tick);
    }

    /**
     * Whether its faction walks wait now, every one of them: after a run of refusals with no walk between, as from a
     * tunnel underground every place its faction play chooses is out of reach (MOVEMENT: on Factions seed 109, main
     * a60f919, Kindred branch mining at Y 19 had walks to many places refused 89 times in seventeen minutes, each
     * place three times). The wait ends early once it has gone more than {@value #STREAK_MOVED} blocks from where the
     * run began, or come up to the surface having begun it below: from there the places may be in reach again.
     */
    boolean paused(long tick, net.minecraft.util.math.BlockPos at, boolean atSurface) {
        if (streakFrom != null && at != null && (streakFrom.getSquaredDistance(at) > (double) STREAK_MOVED * STREAK_MOVED
                || atSurface && !streakAtSurface)) {
            streak = 0;
            pauses = 0;
            pausedUntil = Long.MIN_VALUE / 2;
            streakFrom = null;
            return false;
        }
        return pausedUntil > tick;
    }

    /**
     * Whether it stands at the surface, by what it sees where it stands (F-35i2): the sky's light at its feet, as
     * {@link #saw} takes it. Under a tree's crown, by an open side under rock (a tunnel's mouth, an overhang) it is;
     * in a tunnel or a cave with no light of the sky it is not. Read from the world's height map before, the top of its
     * column was world data it had not perceived: a roof over its head kept it below at a tunnel's mouth it stood in.
     */
    static boolean atSurface(ServerPlayerEntity player) {
        return atSurface(player.getServerWorld().getLightLevel(net.minecraft.world.LightType.SKY, player.getBlockPos()));
    }

    /** At the surface by the sky's light at its feet, {@code skyLightAtFeet}: any light of the sky reaches it there. */
    static boolean atSurface(int skyLightAtFeet) {
        return skyLightAtFeet > 0;
    }

    /** How far below where it last saw the sky it stands, at most, and is not deep below the surface (M-06i2c1). */
    static final int DEEP = 8;
    /** The height it last stood at with the sky's light at its feet (M-06i2c1); {@link Integer#MIN_VALUE} before it has. */
    private int skyY = Integer.MIN_VALUE;

    /** What it sees where it stands: with the sky's light at its feet, it is where it saw the sky from. */
    void saw(ServerPlayerEntity player) {
        if (player.getServerWorld().getLightLevel(net.minecraft.world.LightType.SKY, player.getBlockPos()) > 0) skyY = player.getBlockY();
    }

    /**
     * Whether it stands deep below the surface, by what it has seen: no sky's light at its feet, and more than
     * {@value #DEEP} blocks below where it last saw the sky, as in a mine or a cave it went down into. A hollow, a box
     * round it or a notch dug in a wall where it stood is not; nor, before it has seen the sky at all, is anywhere.
     */
    boolean deepBelow(ServerPlayerEntity player) {
        return skyY != Integer.MIN_VALUE && skyY - player.getBlockY() > DEEP
                && player.getServerWorld().getLightLevel(net.minecraft.world.LightType.SKY, player.getBlockPos()) == 0;
    }

    /** Places set aside in a row, with no walk between, before every faction walk waits; and how far a move ends the wait early. */
    static final int STREAK = 2, STREAK_MOVED = 32;
    /** Every faction walk's first wait, in ticks (half a minute); it doubles with each, to a game day at most. */
    static final long PAUSE = 600;
    private int streak, pauses;
    private long pausedUntil = Long.MIN_VALUE / 2;
    private net.minecraft.util.math.BlockPos streakFrom;
    private boolean streakAtSurface = true;

    /**
     * Faction walks that came back refused, the refusals the back-off counted of them, and those whose move was settled
     * while the walk still ran (there in its chunk, the place set aside, its end seen to be open water), which are no
     * refusal of a move still to be made: the first is the sum of the other two when it holds.
     */
    int blockedWalks, refusalsCounted, blockedSettled;
    /** The task id of the faction walk whose move was settled while it ran, if any: for {@link #blockedSettled}. */
    UUID settledWalk;
    /** Faction walks that ended, however they ended: asked of the navigator at all (F-35j's fixtures). */
    int walksEnded;
    /** For the fixtures (F-35j1): each faction walk that ended, its target and tick, and each choice's tick; the last 64 of each. */
    final java.util.ArrayDeque<Map.Entry<String, Long>> endedForTest = new java.util.ArrayDeque<>();
    final java.util.ArrayDeque<Long> decidedForTest = new java.util.ArrayDeque<>();
    void endedForTest(String target, long tick) { endedForTest.addLast(Map.entry(target, tick)); if (endedForTest.size() > 64) endedForTest.removeFirst(); }
    void decidedForTest(long tick) { decidedForTest.addLast(tick); if (decidedForTest.size() > 64) decidedForTest.removeFirst(); }
    /**
     * For the fixtures (F-22c83): the tick of each faction walk that completed (got where it went, its move settled or
     * not), and of each place set aside as open water it saw; the last 64 of each.
     */
    final java.util.ArrayDeque<Long> completedForTest = new java.util.ArrayDeque<>();
    final java.util.ArrayDeque<Long> overWaterForTest = new java.util.ArrayDeque<>();
    void completedForTest(long tick) { completedForTest.addLast(tick); if (completedForTest.size() > 64) completedForTest.removeFirst(); }
    /**
     * For the fixtures (M-06m): the tick of each move settled because it got there, a walk asked or none (in its chunk, as
     * a swim drifts it there), after which it weighs again at once; the last 64.
     */
    final java.util.ArrayDeque<Long> arrivedForTest = new java.util.ArrayDeque<>();
    void arrivedForTest(long tick) { arrivedForTest.addLast(tick); if (arrivedForTest.size() > 64) arrivedForTest.removeFirst(); }

    /** A walk toward {@code target} got somewhere: its refusals and ways forgotten, and the run of refusals with them. */
    void walked(String target) {
        refused.remove(target);
        ways.remove(target);
        leafBreaks.remove(target);
        streak = 0;
        pauses = 0;
        pausedUntil = Long.MIN_VALUE / 2;
        streakFrom = null;
    }

    /**
     * The steps of a way made toward a target whose walk was refused (MOVEMENT, #120), each a dig, a block put or a walk up
     * to one; after {@value #WAY_STEPS} with the walk still refused, no more are made toward it and the back-off and
     * set-aside take over.
     */
    private final Map<String, Integer> ways = new LinkedHashMap<>();
    static final int WAY_STEPS = 48;

    void wayMade(String target) { ways.merge(target, 1, Integer::sum); }

    int waysMade(String target) { return ways.getOrDefault(target, 0); }

    int refusals(String target) { var r = refused.get(target); return r == null ? 0 : r.times(); }

    /**
     * A walk toward {@code target} that would end in open water it saw (F-35j): set aside at once, with no walk asked, as
     * after {@value #SET_ASIDE} refusals; again later, the wait twice as long. Not a refusal of the walk: no route was
     * searched, and the run of refusals (F-35i) is not counted on.
     */
    void openWater(String target, long tick) {
        openWater(target, tick, null, true);
    }

    /**
     * As above, from {@code at}, at the surface or not: a place set aside as open water counts toward the run of places set
     * aside with no walk between, as a place set aside after its refusals does, so that a coast of such places and land
     * beyond reach pauses every faction walk as F-35i has it (F-35j1: on Factions seed 110 they never counted, and Echo's
     * walks went on to place after place, each refused once).
     */
    void openWater(String target, long tick, net.minecraft.util.math.BlockPos at, boolean atSurface) {
        var before = refused.get(target);
        int times = Math.max(SET_ASIDE, before == null ? 0 : before.times() + 1);
        refused.put(target, new Refusals(times, tick + Math.min(BACKOFF_MAX, BACKOFF << Math.min(times - 1, 10))));
        overWater++;
        overWaterForTest.addLast(tick);
        if (overWaterForTest.size() > 64) overWaterForTest.removeFirst();
        if (streakFrom == null && at != null) {
            streakFrom = at.toImmutable();
            streakAtSurface = atSurface;
        }
        streak++;
        streakPause(tick);
    }

    /** Once {@value #STREAK} places are set aside in a row, or after a pause, every faction walk waits, twice as long each time. */
    private void streakPause(long tick) {
        if (streak >= STREAK || pauses > 0) {
            pausedUntil = tick + Math.min(BACKOFF_MAX, PAUSE << Math.min(pauses, 10));
            pauses++;
            streak = 0;
        }
    }

    /** Places set aside as open water it saw, for the fixtures. */
    int overWater;
    /** Toward a walk's end beyond its sight, the last this many columns out to the edge of its sight all water it saw: open water. */
    static final int OPEN_WATER = 10;
    /** The last walk's end found not open water, and when: looked at again once it changes or a second on. */
    BlockPos dryAt;
    long dryAtTick = Long.MIN_VALUE / 2;

    /** Whether a walk toward {@code target} waits now. */
    boolean waiting(String target, long tick) { var r = refused.get(target); return r != null && r.until() > tick; }

    /** The targets set aside now: refused {@value #SET_ASIDE} times in a row, their wait not over. */
    java.util.Set<String> setAside(long tick) {
        var out = new java.util.HashSet<String>();
        refused.forEach((t, r) -> { if (r.times() >= SET_ASIDE && r.until() > tick) out.add(t); });
        return out;
    }

    /** A founding refused for its name, retried with another. */
    int foundings;

    /** Once a second: the claimed land it stands in and round it, another faction's, as it sees it now. */
    void watch(ServerPlayerEntity player, Map<String, String> claims, String ownFaction, long nowMs) {
        String dimension = player.getServerWorld().getRegistryKey().getValue().toString();
        if (!dimension.equals(Territory.OVERWORLD)) return;
        var here = Territory.Chunk.at(dimension, player.getBlockX(), player.getBlockZ());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var chunk = here.offset(dx, dz);
            String owner = claims.get(chunk.key());
            if (owner == null || owner.equals(ownFaction)) seen.remove(chunk.key());
            else seen.put(chunk.key(), new FactionPlay.Sighting(chunk.key(), owner, nowMs));
        }
    }

    List<FactionPlay.Sighting> seen() { return List.copyOf(seen.values()); }

    /** The players it saw within {@value #MET} blocks, and when; those seen in the last five minutes are the ones it met. */
    private final Map<UUID, Long> met = new LinkedHashMap<>();
    static final int MET = 24;

    /** Once a second: the other players it can see near it. */
    void meet(ServerPlayerEntity player, long nowMs) {
        for (var other : player.getServerWorld().getPlayers()) {
            if (other == player || other.isSpectator() || !other.isAlive()) continue;
            if (other.squaredDistanceTo(player) <= MET * MET && player.canSee(other)) met.put(other.getUuid(), nowMs);
        }
        met.values().removeIf(at -> nowMs - at > 5 * 60_000L);
    }

    List<UUID> met() { return List.copyOf(met.keySet()); }

    /** The slots of its spare common blocks: those beyond the first {@value #KEEP} of each kind, whole stacks only. */
    static List<Integer> spareSlots(ServerPlayerEntity player) { return spareSlots(player, null); }

    /** As above, only of the kinds named in {@code kinds} (all of them when null). */
    static List<Integer> spareSlots(ServerPlayerEntity player, Set<String> kinds) {
        var kept = new LinkedHashMap<String, Integer>();
        var out = new ArrayList<Integer>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            var stack = inventory.main.get(slot);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (!COMMON.contains(id) || stack.getComponentChanges().size() > 0 || kinds != null && !kinds.contains(id)) continue;
            int have = kept.getOrDefault(id, 0);
            if (have < KEEP) kept.put(id, have + stack.getCount());
            else out.add(slot);
        }
        return out;
    }

    /** A faction name and tag of its own, from its name; another on each refused try. */
    static String[] nameFor(String displayName, int attempt) {
        String base = displayName.replaceAll("[^A-Za-z0-9_]", "");
        if (base.length() < 3) base = (base + "Kin").substring(0, Math.max(3, base.length()));
        if (base.length() > 11) base = base.substring(0, 11);
        String name = base + "_Hold" + (attempt == 0 ? "" : String.valueOf(attempt));
        if (name.length() > 16) name = name.substring(0, 16);
        String letters = base.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT);
        String tag = (letters + "XXX").substring(0, 3) + (attempt == 0 ? "" : String.valueOf(attempt % 10));
        return new String[] {name, tag};
    }

    /** Where a move goes: the nearest spot just inside its chunk, on the ground, or the faction's home; null when it has nowhere. */
    static BlockPos destination(FactionPlay.Choice move, ServerPlayerEntity player) {
        var world = player.getServerWorld();
        if (move.kind() == FactionPlay.Kind.DEFEND) {
            // A home is "world:x,y,z" as /faction sethome writes it.
            String home = move.target();
            int colon = home.lastIndexOf(':');
            if (colon < 0) return null;
            String[] xyz = home.substring(colon + 1).split(",");
            if (xyz.length != 3 || !home.substring(0, colon).equals(world.getRegistryKey().getValue().toString())) return null;
            try {
                return new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
            } catch (NumberFormatException bad) {
                return null;
            }
        }
        if (move.target().isEmpty()) return null;
        var chunk = Territory.Chunk.of(move.target());
        if (!chunk.dimension().equals(world.getRegistryKey().getValue().toString())) return null;
        int[] end = legEnd(chunk, player);
        int x = end[0], z = end[1];
        boolean far = end[2] == 1;
        int y = player.getBlockY();
        if (world.isChunkLoaded(x >> 4, z >> 4)) {
            // Ground to stand on there that it can see, nearest its own height first (a trunk or a roof above is not the
            // way); else, at a leg's end, the top of the column if it can see it; otherwise its own height, for the route to
            // find the way. What it cannot see from where it stands sets nothing (F-35m): a leg's end is past its sweep.
            var looks = WorldKnowledge.looks(player, GROUND_LOOKS + 1);
            var under = new java.util.ArrayList<BlockPos>();
            for (int d = 0; d <= 8; d++) {
                under.add(new BlockPos(x, y + d - 1, z));
                if (d > 0) under.add(new BlockPos(x, y - d - 1, z));
            }
            // Room to stand is asked before the sight line only to spare it: a cell with none is no walk's end.
            var ground = looks.firstSeen(under, below -> standable(world, below.getX(), below.getY() + 1, below.getZ()));
            if (ground.isPresent()) return new BlockPos(x, ground.get().getY() + 1, z);
            if (far && looks.rays() <= GROUND_LOOKS) {
                var top = looks.ground(x, z);
                if (top.isPresent()) return new BlockPos(x, top.get().getY() + 1, z);
            }
            // A leg's end it sees no ground at (M-06i2c2): the farthest ground it sees along the way and could stand on, as a
            // player walks to the brink it can see rather than into the air past it. At its own height there a leg ended in
            // mid-air over a valley or inside a hillside, where no route ends: on Factions seed 20261030, 211 of 919 legs
            // refused for no route ended in mid-air. Where it sees none along the way, the leg is as before.
            if (far) {
                var back = backAlong(player, x, z, WorldKnowledge.looks(player, BACK_LOOKS));
                // Not from where it stands: from the brink of what it stands on, toward the leg, as a player on a summit
                // steps to its edge to look down (F-35m's look over the brink) (M-06i2c4). From a summit's middle its own
                // edge hides the slope below: on Factions seed 20261030 the brink hid it on 443 and 321 of the legs M-06i2c2
                // left as they were (M-06i2c3's trace, bases on and off).
                if (back == null) back = backAlong(player, x, z, WorldKnowledge.looksOverTheBrink(player, x - player.getX(), z - player.getZ(), BACK_LOOKS));
                if (back != null) return back;
            }
        }
        return new BlockPos(x, y, z);
    }

    /**
     * A move's end in its chunk, as {@link #destination} has it before it looks for ground: x, z, and 1 if that is a
     * leg's end, short of the chunk, else 0.
     */
    private static int[] legEnd(Territory.Chunk chunk, ServerPlayerEntity player) {
        // The nearest spot three blocks inside the chunk: standing in it is all a claim or capture asks, and a walk that
        // ends within a block of it is in it.
        int x = Math.clamp(player.getBlockX(), chunk.x() * 16 + INSET, chunk.x() * 16 + 15 - INSET);
        int z = Math.clamp(player.getBlockZ(), chunk.z() * 16 + INSET, chunk.z() * 16 + 15 - INSET);
        // A long walk goes in legs: the next 48 blocks toward the chunk, each leg a walk of its own.
        double far = Math.hypot(x - player.getX(), z - player.getZ());
        if (far > LEG) {
            x = (int) Math.floor(player.getX() + (x - player.getX()) * LEG / far);
            z = (int) Math.floor(player.getZ() + (z - player.getZ()) * LEG / far);
        }
        return new int[] {x, z, far > LEG ? 1 : 0};
    }

    /** Leaves broken toward one target before its walk gets somewhere (M-06i2c5). */
    static final int LEAF_BREAKS = 3;
    /** How far from its eyes a leaf it breaks may be: a player's reach (M-06i2c5). */
    static final double LEAF_REACH = 4.5;

    /**
     * The leaves nearest its eyes, within its reach, that stand in its sight line to the ground along a leg it sees no
     * ground on (M-06i2c5), as a player pushes through a spruce's lower branches to see the slope past them; null when
     * none, when the leg is not such a leg, or when it has broken {@value #LEAF_BREAKS} toward this target already. Only
     * the blocks its sight lines met are asked: one look along the leg's end and the columns {@link #backAlong} looks at,
     * nothing of the world past what stopped its eyes. A leaf it was refused (someone's claim, the server's edit policy)
     * is not asked again.
     */
    BlockPos leavesInTheWay(FactionPlay.Choice move, ServerPlayerEntity player, BlockPos to) {
        if (move.kind() == FactionPlay.Kind.DEFEND || move.target().isEmpty()) return null;
        if (leafBreaks.getOrDefault(move.target(), 0) >= LEAF_BREAKS) return null;
        var world = player.getServerWorld();
        var chunk = Territory.Chunk.of(move.target());
        if (!chunk.dimension().equals(world.getRegistryKey().getValue().toString())) return null;
        int[] end = legEnd(chunk, player);
        // Only a leg left as it was: at its own height, at the leg's end, no ground seen along the way.
        if (end[2] != 1 || to.getX() != end[0] || to.getZ() != end[1] || to.getY() != player.getBlockY()) return null;
        var looks = WorldKnowledge.looks(player, BACK_LOOKS + 1);
        var columns = new java.util.ArrayList<int[]>();
        columns.add(new int[] {end[0], end[1]});
        double dx = end[0] - player.getX(), dz = end[1] - player.getZ(), length = Math.hypot(dx, dz);
        for (int step = 1; step <= BACK_LOOKS; step++) {
            double at = length - step * BACK_STEP;
            if (at < BACK_STEP) break;
            columns.add(new int[] {(int) Math.floor(player.getX() + dx * at / length), (int) Math.floor(player.getZ() + dz * at / length)});
        }
        var eye = looks.eye();
        BlockPos nearest = null;
        double best = LEAF_REACH * LEAF_REACH;
        for (int[] c : columns) {
            if (!world.isChunkLoaded(c[0] >> 4, c[1] >> 4)) continue;
            if (looks.ground(c[0], c[1]).isPresent()) continue;
            var met = looks.metToward(c[0], c[1]).orElse(null);
            if (met == null || leafRefused.contains(met.asLong()) || !world.getBlockState(met).isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) continue;
            double d = eye.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(met));
            if (d <= best) { best = d; nearest = met; }
        }
        return nearest;
    }

    /** Leaves broken toward each target since its walk last got somewhere, and leaves it was refused (M-06i2c5). */
    private final Map<String, Integer> leafBreaks = new LinkedHashMap<>();
    private final java.util.Set<Long> leafRefused = new java.util.HashSet<>();
    /** For the evidence: leaves it broke and was refused on its faction legs, in all (M-06i2c5). */
    int leavesBroken, leavesRefusedCount;
    /** The leaf breaks it proposed, by task: the target each was toward and the leaf (M-06i2c5); the last 16. */
    final Map<UUID, Map.Entry<String, BlockPos>> leafTasks = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID, Map.Entry<String, BlockPos>> eldest) { return size() > 16; }
    };

    /** A leaf broken toward {@code target}: one of its {@value #LEAF_BREAKS}. */
    void leafBroken(String target) {
        leafBreaks.merge(target, 1, Integer::sum);
        leavesBroken++;
    }

    /** A leaf at {@code at} it was refused or could not break: not asked again; it counts toward the target's breaks. */
    void leafRefused(String target, BlockPos at) {
        leafBreaks.merge(target, 1, Integer::sum);
        if (leafRefused.size() < SEEN_MAX) leafRefused.add(at.asLong());
        leavesRefusedCount++;
    }

    /** The steps back along a leg, from its end toward where it stands, at which it looks for ground (M-06i2c2). */
    static final int BACK_STEP = 6, BACK_LOOKS = 6;

    /**
     * The farthest column along the line from where it stands to a leg's end at ({@code x}, {@code z}), stepped back from
     * the end {@value #BACK_STEP} blocks at a time, whose top it sees with {@code looks} and could stand on; null if
     * none. At most {@value #BACK_LOOKS} sight lines a look, one a column; nothing of a column it does not see is read.
     */
    private static BlockPos backAlong(ServerPlayerEntity player, int x, int z, WorldKnowledge.Looks looks) {
        var world = player.getServerWorld();
        double dx = x - player.getX(), dz = z - player.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1e-6) return null;
        for (int step = 1; step <= BACK_LOOKS; step++) {
            double at = length - step * BACK_STEP;
            if (at < BACK_STEP) break;
            int cx = (int) Math.floor(player.getX() + dx * at / length);
            int cz = (int) Math.floor(player.getZ() + dz * at / length);
            if (!world.isChunkLoaded(cx >> 4, cz >> 4)) continue;
            var top = looks.ground(cx, cz);
            if (top.isPresent() && standable(world, cx, top.get().getY() + 1, cz)) return new BlockPos(cx, top.get().getY() + 1, cz);
        }
        return null;
    }

    /** Sight lines to ground near its own height at a walk's end; one more for the column's top (F-35m). */
    private static final int GROUND_LOOKS = 4;

    /** Solid ground under {@code y} and room for a player above it. */
    private static boolean standable(net.minecraft.server.world.ServerWorld world, int x, int y, int z) {
        var feet = new BlockPos(x, y, z);
        return world.getBlockState(feet.down()).isSideSolidFullSquare(world, feet.down(), net.minecraft.util.math.Direction.UP)
                && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty() && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                && world.getFluidState(feet).isEmpty();
    }

    /** Whether a choice is a walk: defending, scouting, or a claim or capture of a chunk it is not standing in. */
    static boolean moves(FactionPlay.Choice choice, ServerPlayerEntity player) {
        return switch (choice.kind()) {
            case DEFEND, SCOUT -> true;
            case CLAIM, CAPTURE -> !Territory.Chunk.at(player.getServerWorld().getRegistryKey().getValue().toString(), player.getBlockX(), player.getBlockZ())
                    .key().equals(choice.target());
            default -> false;
        };
    }

    /** Whether it has got where a move goes: inside the chunk, or within four blocks of home. */
    static boolean arrived(FactionPlay.Choice move, ServerPlayerEntity player, BlockPos to) {
        if (move.kind() == FactionPlay.Kind.DEFEND) return player.getBlockPos().getSquaredDistance(to) <= 16;
        var here = Territory.Chunk.at(player.getServerWorld().getRegistryKey().getValue().toString(), player.getBlockX(), player.getBlockZ());
        return here.key().equals(move.target());
    }

    static UUID taskId(UUID player, FactionPlay.Choice move) {
        return UUID.nameUUIDFromBytes(("faction:" + player + ":" + move.kind() + ":" + move.target()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
