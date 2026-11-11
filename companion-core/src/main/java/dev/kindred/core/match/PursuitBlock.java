package dev.kindred.core.match;

import dev.kindred.core.VanillaIds;

import dev.kindred.core.construction.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * In plain terms: how a player running from a pursuer shuts the way behind it, as players do in a manhunt: when it
 * is going through a way only one block wide (a tunnel, a gap between walls, a doorway with no door) and the
 * pursuer is coming along it behind, it puts two of its own blocks into the gap it just came through, so the
 * pursuer has to dig through or go round.
 *
 * Technical: The gap to fill behind a fleeing player (M-02b, blocking pursuit). The gap is the cell one step from
 * its feet toward the pursuer, along the axis that points most toward it. It is filled only where the way is one
 * block wide: the gap and the cell above are open, and both cells beside each of them, across the way, are solid.
 * Where the gap has open sides the pursuer would only walk round the block, so nothing is spent. The cells still to
 * fill come back feet first, so the lower block rests on the floor and the upper one on the lower; with the lower one
 * in, the head cell is what is left. Pure; the adapter
 * supplies what is solid and open, and which of the player's own blocks it may spend.
 */
public final class PursuitBlock {
    private PursuitBlock() { }

    /** What the ground is at a cell, as the player sees it. */
    public interface Ground {
        /** Open: a player can stand or walk through it (air, or the like). */
        boolean open(Pos cell);

        /** Solid: a full block a player cannot pass through. */
        boolean solid(Pos cell);
    }

    /** Blocks a player spends to shut a way behind it: plain fillers of no other worth to it. Gravity blocks are not among them. */
    public static final Set<String> FILLERS = Set.of(VanillaIds.COBBLESTONE, VanillaIds.COBBLED_DEEPSLATE, VanillaIds.DIRT,
            VanillaIds.COARSE_DIRT, VanillaIds.STONE, VanillaIds.ANDESITE, VanillaIds.DIORITE, VanillaIds.GRANITE,
            VanillaIds.TUFF, VanillaIds.NETHERRACK, VanillaIds.DEEPSLATE);

    /** How near the pursuer must be for the gap to be worth shutting, and how far: it is coming, but not yet upon it. */
    public static final double NEAREST = 3.5, FARTHEST = 16;

    /**
     * The cells still to fill behind a player at {@code feet} with its pursuer {@code dx, dz} away (the pursuer's position
     * less the player's), feet first; none when the way behind is not one block wide, or the pursuer is not coming
     * along it, or is too near or too far.
     */
    public static List<Pos> behind(Pos feet, double dx, double dz, Ground ground) {
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < NEAREST || distance > FARTHEST) return List.of();
        // One step toward the pursuer, along the axis it is most along.
        int sx = 0, sz = 0;
        if (Math.abs(dx) >= Math.abs(dz)) sx = dx > 0 ? 1 : -1;
        else sz = dz > 0 ? 1 : -1;
        // It must be coming along the way, not from the side: most of the way to it is along that axis.
        double along = Math.abs(sx != 0 ? dx : dz), across = Math.abs(sx != 0 ? dz : dx);
        if (across > along * 0.5 + 1) return List.of();
        Pos gap = new Pos(feet.x() + sx, feet.y(), feet.z() + sz);
        Pos head = new Pos(gap.x(), gap.y() + 1, gap.z());
        // Half shut already (its lower block placed): the head cell is what is left. Shut: nothing.
        if (!ground.open(head)) return List.of();
        boolean lowerOpen = ground.open(gap);
        if (!lowerOpen && !ground.solid(gap)) return List.of();
        // Something to rest the lower block on.
        if (lowerOpen && !ground.solid(new Pos(gap.x(), gap.y() - 1, gap.z()))) return List.of();
        // One wide: solid on both sides across the way, at the feet and the head.
        int ax = sz, az = sx;
        for (Pos cell : List.of(gap, head)) {
            if (!ground.solid(new Pos(cell.x() + ax, cell.y(), cell.z() + az)) || !ground.solid(new Pos(cell.x() - ax, cell.y(), cell.z() - az))) {
                return List.of();
            }
        }
        var cells = new ArrayList<Pos>();
        if (lowerOpen) cells.add(gap);
        cells.add(head);
        return List.copyOf(cells);
    }
}
