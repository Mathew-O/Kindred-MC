package dev.kindred.core;

/**
 * In plain terms: personal space while following someone: hold still when close enough, catch up when too
 * far, back off when too close, and walk at the owner's shoulder rather than right behind.
 *
 * Technical: Personal-space policy. It proposes movement only; the adapter must validate and authorize a
 * route.
 */
public final class FollowSpacing {
    public enum Motion { HOLD, APPROACH, YIELD }

    private FollowSpacing() { }

    /** Separate start/stop distances avoid shuffling each time the owner moves a fraction of a block. */
    public static Motion next(Motion previous, double distanceSquared) {
        if (previous == null || !Double.isFinite(distanceSquared) || distanceSquared < 0) {
            throw new IllegalArgumentException("Follow spacing needs a valid state and squared distance");
        }
        if (distanceSquared < 1.25 * 1.25) return Motion.YIELD;
        if (previous == Motion.YIELD && distanceSquared < 2 * 2) return Motion.YIELD;
        if (previous == Motion.APPROACH && distanceSquared > 2.75 * 2.75) return Motion.APPROACH;
        if (distanceSquared > 3.75 * 3.75) return Motion.APPROACH;
        return Motion.HOLD;
    }

    /** A shoulder position behind the owner's travel heading; side is stable for each companion. */
    public static Offset offset(double headingRadians, int side) {
        if (!Double.isFinite(headingRadians) || (side != -1 && side != 0 && side != 1)) {
            throw new IllegalArgumentException("Invalid follow heading or side");
        }
        double forwardX = -Math.sin(headingRadians);
        double forwardZ = Math.cos(headingRadians);
        return new Offset(-forwardX * 2.25 + forwardZ * side * 1.25,
                -forwardZ * 2.25 - forwardX * side * 1.25);
    }

    public record Offset(double x, double z) { }
}
