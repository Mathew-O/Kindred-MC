package dev.kindred.fabric;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In plain terms: who counts toward a person's night (E-70a). An AI player that cannot be in bed (in a fight, with no
 * bed to hand, busy underground) is left out of the count of players who must sleep for the night to pass, as vanilla
 * leaves out spectators, so it never holds up a person's night. One on its way to its bed counts, for at most
 * {@link dev.kindred.core.survival.Nights#TO_BED_LIMIT} ticks, so the person's night waits that long for it at most.
 * People are counted exactly as vanilla counts them. An operator who wants every AI player counted, in bed or not, sets
 * {@code aiPlayersCountForSleep=true} in edits.properties.
 *
 * Technical: Read on the server thread only, by {@code SleepCountMixin} in {@code SleepManager.update}, which vanilla
 * calls when a player lies down, gets up, joins or leaves. A change to who is on the way to bed asks the Overworld to
 * count again. The switch is set by the runtime from edits.properties as it starts; default false.
 */
public final class SleepCount {
    private static volatile boolean aiPlayersCount;
    /** AI players' bodies on their way to bed, and the server tick their place in the count lapses at. */
    private static final Map<UUID, Long> TO_BED = new ConcurrentHashMap<>();

    private SleepCount() { }

    /** Whether {@code player} is left out of the sleeping count, as a spectator is (an AI player that cannot be in bed). */
    public static boolean leftOut(ServerPlayerEntity player) {
        boolean ai = player instanceof PreparedNativePlayer;
        boolean inBed = player.isSleeping() || ai && TO_BED.containsKey(player.getUuid());
        return dev.kindred.core.survival.Nights.leftOutOfSleepCount(ai, inBed, aiPlayersCount);
    }

    /**
     * {@code body} on its way to bed until {@code until} (a server tick), or not on its way ({@code until} negative); the
     * Overworld counts again when that changes.
     */
    static void toBed(MinecraftServer server, UUID body, long until) {
        boolean changed = until < 0 ? TO_BED.remove(body) != null : TO_BED.put(body, until) == null;
        if (changed) recount(server);
    }

    /** Places in the count that lapsed by {@code tick} let go, and the Overworld counted again if any did. */
    static void tick(MinecraftServer server, long tick) {
        if (TO_BED.isEmpty()) return;
        if (TO_BED.values().removeIf(until -> until <= tick)) recount(server);
    }

    /** Every place in the count let go (the runtime stopping). */
    static void clear(MinecraftServer server) {
        if (TO_BED.isEmpty()) return;
        TO_BED.clear();
        recount(server);
    }

    private static void recount(MinecraftServer server) {
        var overworld = server.getWorld(World.OVERWORLD);
        if (overworld != null) overworld.updateSleepingPlayers();
    }

    /** The operator's {@code aiPlayersCountForSleep}. */
    static void aiPlayersCount(boolean count) { aiPlayersCount = count; }

    static boolean aiPlayersCount() { return aiPlayersCount; }
}
