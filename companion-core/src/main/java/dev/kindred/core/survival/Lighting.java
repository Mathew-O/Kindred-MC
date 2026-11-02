package dev.kindred.core.survival;

import dev.kindred.core.construction.Pos;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Lighting an approved work area (E-06), as players light theirs: hostile mobs spawn only where no block light reaches
 * (block light 0, since 1.18), so every floor cell the player walks has a torch within reach of its light. A torch
 * gives 14 and its light falls by one a step, so a cell thirteen steps from it is lit; walls and corners take steps,
 * so a torch is counted as lighting only what lies {@link #COVER} steps off. Pure: the adapter reads the world's own
 * light again after every torch, so the plan is only where the next one goes.
 */
public final class Lighting {
    private Lighting() { }

    /** A torch's light. */
    public static final int TORCH = 14;
    /** The steps off a torch counted as lit by it, a margin under the thirteen its light reaches in the open. */
    public static final int COVER = 10;

    /** Steps between two cells, as light spreads: along the three axes, one block at a time. */
    public static int steps(Pos a, Pos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    /**
     * Where the next torch goes, of the floor cells no block light reaches ({@code dark}, where a player stands): the one
     * that would light the most of the others, the nearest to {@code feet} of those, then the first. Empty when none
     * is dark.
     */
    public static Optional<Pos> next(List<Pos> dark, Pos feet) {
        return dark.stream().max(Comparator.<Pos>comparingInt(c -> (int) dark.stream().filter(o -> steps(c, o) <= COVER).count())
                .thenComparingInt(c -> -steps(c, feet)));
    }

    /** What it says once its area is lit: "I lit the work area: 3 torches." */
    public static String said(int torches) {
        return "I lit the work area: " + torches + (torches == 1 ? " torch." : " torches.");
    }
}
