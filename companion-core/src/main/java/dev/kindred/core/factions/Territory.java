package dev.kindred.core.factions;

import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.Map;
import java.util.UUID;

/**
 * In plain terms: the rules for claiming land in a faction world: a chunk at a time, in the overworld, inside the border
 * and at least 500 blocks from spawn, touching the faction's own land after its first chunk, never next to another
 * unrelated faction's land, within the faction's capacity, for 100 coins; and who may change what on claimed land.
 *
 * Technical: Pure rules over the claims, faction, power and coin ledgers (M-06d), the same for people and AI players.
 * Chunks are keyed as {@link ClaimLedger#chunk}. Safe zones (M-06f) are a set of chunks the adapter passes in; they are
 * never claimed. Protection: on a claimed chunk only its faction's members may break, place or open anything; outsiders
 * use nothing, a claim's own public flags aside (none by default); a vulnerable chunk is protected exactly as a secure
 * one until it is captured (M-06e).
 */
public final class Territory {
    public static final String OVERWORLD = "minecraft:overworld";

    private Territory() {}

    /** A chunk by its dimension and chunk coordinates. */
    public record Chunk(String dimension, int x, int z) {
        public String key() { return ClaimLedger.chunk(dimension, x, z); }

        public static Chunk of(String key) {
            int b = key.lastIndexOf(':'), a = key.lastIndexOf(':', b - 1);
            return new Chunk(key.substring(0, a), Integer.parseInt(key.substring(a + 1, b)), Integer.parseInt(key.substring(b + 1)));
        }

        public static Chunk at(String dimension, int blockX, int blockZ) { return new Chunk(dimension, blockX >> 4, blockZ >> 4); }

        public Chunk offset(int dx, int dz) { return new Chunk(dimension, x + dx, z + dz); }

        /** The nearest any of its blocks comes to (0, 0) on the ground, in blocks. */
        double nearestToSpawn() {
            int minX = x * 16, maxX = minX + 15, minZ = z * 16, maxZ = minZ + 15;
            int nx = minX > 0 ? minX : maxX < 0 ? maxX : 0, nz = minZ > 0 ? minZ : maxZ < 0 ? maxZ : 0;
            return Math.sqrt((double) nx * nx + (double) nz * nz);
        }

        /** Whether every block of it lies inside a border {@code border} blocks across, centred on (0, 0). */
        boolean inside(int border, int scale) {
            double half = border / 2.0 / scale;
            return x * 16 >= -half && x * 16 + 16 <= half && z * 16 >= -half && z * 16 + 16 <= half;
        }
    }

    /** A claim's outcome: the events to write, or why it was refused. */
    public record Decision(java.util.List<WorldEvent> events, String reason) {
        public Decision { events = java.util.List.copyOf(events); }
        public boolean accepted() { return !events.isEmpty(); }
        static Decision refused(String reason) { return new Decision(java.util.List.of(), reason); }
    }

    /** Whether {@code chunk} is inside the playable area: the border in its dimension (the Nether's an eighth). */
    public static boolean playable(Chunk chunk, FactionSettings settings) {
        int scale = chunk.dimension().equals("minecraft:the_nether") ? 8 : 1;
        return chunk.inside(settings.border(), scale);
    }

    /**
     * {@code player} claims {@code chunk} for its faction. {@code safe} the chunks of the safe zones. {@code coins} its
     * balance; the claim's cost is written beside the claim as a {@code coins} event (M-06g reads them).
     */
    public static Decision claim(FactionBook factions, PowerBook powers, Map<String, String> claims, java.util.Set<String> safe, long coins,
                                 FactionSettings settings, UUID player, Chunk chunk, Factions.At at) {
        var faction = factions.of(player).orElse(null);
        if (faction == null) return Decision.refused("You are not in a faction");
        if (!faction.members().get(player).atLeast(Faction.Rank.OFFICER)) return Decision.refused("Only an officer or the leader claims land");
        if (!chunk.dimension().equals(OVERWORLD)) return Decision.refused("Land is claimed in the overworld only");
        if (safe.contains(chunk.key())) return Decision.refused("A safe zone is never claimed");
        if (!playable(chunk, settings)) return Decision.refused("Beyond the border");
        if (chunk.nearestToSpawn() < settings.spawnExclusion()) return Decision.refused("Within " + settings.spawnExclusion() + " blocks of spawn");
        String owner = claims.get(chunk.key());
        if (owner != null) return Decision.refused(owner.equals(faction.id().toString()) ? "Already yours" : "Claimed by another faction");
        String id = faction.id().toString();
        long held = claims.values().stream().filter(id::equals).count();
        int power = PowerRules.factionPower(factions, powers, faction.id(), settings);
        if (held + 1 > PowerRules.capacity(power, settings)) {
            return Decision.refused("Over capacity: " + held + " claimed of " + PowerRules.capacity(power, settings) + " (power " + power + ", cap " + settings.claimCap() + ")");
        }
        if (held > 0 && !touchesOwn(claims, id, chunk)) return Decision.refused("Not touching your land: claims grow from your own chunks");
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            String other = claims.get(chunk.offset(dx, dz).key());
            if (other == null || other.equals(id)) continue;
            UUID otherId = UUID.fromString(other);
            if (factions.relation(faction.id(), otherId) != Faction.Relation.ALLY) return Decision.refused("Leave a chunk between your land and another faction's");
        }
        if (coins < settings.claimCost()) return Decision.refused("A claim costs " + settings.claimCost() + " coins; you have " + coins);
        var claim = WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), ClaimLedger.CLAIM, player, id, chunk.key(), Map.of());
        var cost = Coins.change(new Coins.Book(Map.of(player, coins), 0), player, -settings.claimCost(), "claim " + chunk.key(), at, chunk.key()).orElseThrow();
        return new Decision(java.util.List.of(claim, cost), "Claimed " + chunk.key() + " for " + faction.name() + " (" + (held + 1) + " / "
                + PowerRules.capacity(power, settings) + ")");
    }

    /** An officer gives up one of its faction's chunks; no coins come back. */
    public static Decision unclaim(FactionBook factions, Map<String, String> claims, UUID player, Chunk chunk, Factions.At at) {
        var faction = factions.of(player).orElse(null);
        if (faction == null) return Decision.refused("You are not in a faction");
        if (!faction.members().get(player).atLeast(Faction.Rank.OFFICER)) return Decision.refused("Only an officer or the leader gives up land");
        if (!faction.id().toString().equals(claims.get(chunk.key()))) return Decision.refused("Not your land");
        return new Decision(java.util.List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), ClaimLedger.UNCLAIM, player,
                faction.id().toString(), chunk.key(), Map.of())), "Gave up " + chunk.key());
    }

    /** Whether {@code chunk} shares an edge with one of {@code faction}'s chunks. */
    public static boolean touchesOwn(Map<String, String> claims, String faction, Chunk chunk) {
        return faction.equals(claims.get(chunk.offset(1, 0).key())) || faction.equals(claims.get(chunk.offset(-1, 0).key()))
                || faction.equals(claims.get(chunk.offset(0, 1).key())) || faction.equals(claims.get(chunk.offset(0, -1).key()));
    }

    /** What a player may do to blocks and containers in a chunk. */
    public enum Access { ANY, MEMBERS_ONLY, OPERATORS_ONLY }

    /** Who may change {@code chunk}: anyone in the wilderness, its faction's members on claimed land, operators in a safe zone. */
    public static Access access(Map<String, String> claims, java.util.Set<String> safe, Chunk chunk) {
        if (safe.contains(chunk.key())) return Access.OPERATORS_ONLY;
        return claims.containsKey(chunk.key()) ? Access.MEMBERS_ONLY : Access.ANY;
    }

    /**
     * Whether {@code player} may break, place or open things in {@code chunk}: in the wilderness anyone; on claimed land its
     * faction's members only, whether or not the chunk is exposed (captured land is its new owner's); in a safe zone
     * operators only.
     */
    public static boolean mayChange(FactionBook factions, Map<String, String> claims, java.util.Set<String> safe, UUID player, boolean operator, Chunk chunk) {
        return switch (access(claims, safe, chunk)) {
            case ANY -> true;
            case OPERATORS_ONLY -> operator;
            case MEMBERS_ONLY -> factions.of(player).map(f -> f.id().toString().equals(claims.get(chunk.key()))).orElse(false);
        };
    }

    /**
     * Whether something may cross from {@code from} into {@code to} (fluid flow, a piston's push, a hopper's pull, a
     * projectile's effect): always within one chunk's owner, never into protected land of another owner. The wilderness
     * receives anything; a safe zone nothing from outside it.
     */
    public static boolean mayCross(Map<String, String> claims, java.util.Set<String> safe, Chunk from, Chunk to) {
        if (from.equals(to)) return true;
        boolean toSafe = safe.contains(to.key()), fromSafe = safe.contains(from.key());
        if (toSafe) return fromSafe;
        String owner = claims.get(to.key());
        if (owner == null) return true;
        return owner.equals(claims.get(from.key()));
    }
}
