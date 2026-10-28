package dev.kindred.core.tactics;

import dev.kindred.core.tactics.Melee.Move;

import java.util.Optional;

/**
 * The move a demonstrating player made in a tick of a fight (E-41e), worked out from what the server sees it do, as a
 * label for {@link LearnedMelee}: eating, a swing, a raised shield, and otherwise how it moved against its opponent
 * (closing, opening fast or slowly, or going round). A tick it stood still and did nothing is no label. Pure.
 */
public final class MoveLabels {
    /** Closing faster than this a tick is closing in. */
    static final double CLOSING = 0.08;
    /** Opening faster than this a tick is getting away (a sprint's pace); slower, but more than {@link #OPENING}, stepping back. */
    static final double FLEEING = 0.2;
    static final double OPENING = 0.05;
    /** Going round faster than this a tick, sideways to the opponent, is strafing. */
    static final double ROUND = 0.08;

    private MoveLabels() { }

    /**
     * The move from one tick: the distance to the opponent at its start and end, the player's motion sideways to the
     * opponent's bearing (either way), and whether it swung, held a raised shield or was eating.
     */
    public static Optional<Move> label(double distanceBefore, double distanceAfter, double sideways, boolean swung, boolean blocking, boolean eating) {
        if (eating) return Optional.of(Move.EAT);
        if (swung) return Optional.of(Move.STRIKE);
        if (blocking) return Optional.of(Move.BLOCK);
        double change = distanceAfter - distanceBefore;
        if (change < -CLOSING) return Optional.of(Move.APPROACH);
        if (change > FLEEING) return Optional.of(Move.RETREAT);
        if (Math.abs(sideways) > ROUND) return Optional.of(Move.STRAFE);
        if (change > OPENING) return Optional.of(Move.BACK_OFF);
        return Optional.empty();
    }
}
