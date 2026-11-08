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
    /** Where it is going, and why: a claim beside its land, a seen border to scout, or home. */
    FactionPlay.Choice move;
    /** What it chose to ask for, waiting for its turn to act. */
    FactionPlay.Choice chosen;
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
    static List<Integer> spareSlots(ServerPlayerEntity player) {
        var kept = new LinkedHashMap<String, Integer>();
        var out = new ArrayList<Integer>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            var stack = inventory.main.get(slot);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (!COMMON.contains(id) || stack.getComponentChanges().size() > 0) continue;
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
        int y = player.getBlockY();
        if (world.isChunkLoaded(x >> 4, z >> 4)) {
            // Ground to stand on there, nearest its own height first (a trunk or a roof above is not the way); else, at a
            // leg's end, the top of the column; otherwise its own height, for the route to find the way.
            Integer ground = null;
            for (int d = 0; d <= 8 && ground == null; d++) {
                if (standable(world, x, y + d, z)) ground = y + d;
                else if (d > 0 && standable(world, x, y - d, z)) ground = y - d;
            }
            if (ground != null) y = ground;
            else if (far > LEG) y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        return new BlockPos(x, y, z);
    }

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
