package dev.kindred.fabric;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * In plain terms: someone told, beside the log, what the AI players' runtime has just seen happen: a player died, a
 * task ended. Nothing told here changes what the players do, and with no one listening nothing is told.
 *
 * Technical: called on the server thread at the moments the runtime writes the matching log lines, with the facts it
 * has at hand. The runtime does not know who listens; an unattended evaluation run (F-26) is one listener.
 */
interface RunObserver {
    /** Listens to nothing. */
    RunObserver NONE = new RunObserver() { };

    /**
     * A native player died, its body still where it fell: {@code cause} as the game records it, the goal's stage, the
     * stance and habits in force, and the task it was on ("nothing" when idle).
     */
    default void died(String player, ServerPlayerEntity body, String cause, String stage, String stance, String habits, String task) { }

    /** A task ended: its skill, how ({@code COMPLETED}, {@code BLOCKED}, {@code CANCELLED}) and its label, if it had one. */
    default void ended(String player, String skill, String state, String label) { }

    /** A bridge run ended (E-71a), however it ended: the blocks it placed and the ticks it took. */
    default void bridged(String player, long blocks, long ticks) { }

    /** The run's result so far, as its file will hold it, for the watch page (E-48); none for a listener with no result. */
    default String resultSoFar() { return null; }
}
