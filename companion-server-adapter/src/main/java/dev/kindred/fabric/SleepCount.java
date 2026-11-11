package dev.kindred.fabric;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * In plain terms: who counts toward a person's night (E-70a). An AI player that is not in bed (in a fight, with no bed
 * to hand, busy underground) is left out of the count of players who must sleep for the night to pass, as vanilla
 * leaves out spectators, so it never holds up a person's night. People are counted exactly as vanilla counts them. An
 * operator who wants every AI player counted sets {@code aiPlayersCountForSleep=true} in edits.properties.
 *
 * Technical: Read on the server thread only, by {@code SleepCountMixin} in {@code SleepManager.update}. The switch is
 * set by the runtime from edits.properties as it starts; default false.
 */
public final class SleepCount {
    private static volatile boolean aiPlayersCount;

    private SleepCount() { }

    /** Whether {@code player} is left out of the sleeping count, as a spectator is (an AI player not in bed). */
    public static boolean leftOut(ServerPlayerEntity player) {
        return dev.kindred.core.survival.Nights.leftOutOfSleepCount(player instanceof PreparedNativePlayer, player.isSleeping(), aiPlayersCount);
    }

    /** The operator's {@code aiPlayersCountForSleep}. */
    static void aiPlayersCount(boolean count) { aiPlayersCount = count; }

    static boolean aiPlayersCount() { return aiPlayersCount; }
}
