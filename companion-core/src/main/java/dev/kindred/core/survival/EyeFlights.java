package dev.kindred.core.survival;

import java.util.Optional;

/**
 * Where two watched eye-of-ender flights cross, as players triangulate a stronghold: each throw is a point on the
 * ground and the heading the eye flew off along. Only what the player saw goes in; the crossing is an estimate, good
 * to a chunk or two when the throws were well apart and their headings far from parallel.
 */
public final class EyeFlights {
    /** Headings closer than this (in degrees) cross too far off to trust: throw again from further to the side. */
    public static final double MIN_ANGLE = 4.0;
    /** A crossing further than this behind either throw is a misreading, not a stronghold. */
    static final double MAX_BEHIND = 16.0;
    /** Strongholds lie within this distance of the world's centre (the outermost ring ends near 24,000). */
    static final double MAX_RANGE = 24_400.0;

    /** One flight: where it was thrown from and the way the eye went (any length). */
    public record Flight(double x, double z, double dx, double dz) {
        public Flight {
            if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(dx) || !Double.isFinite(dz) || dx == 0 && dz == 0) {
                throw new IllegalArgumentException("A flight needs a finite point and a heading");
            }
        }

        /** The heading in degrees, Minecraft's yaw convention (0 south, 90 west). */
        public double yaw() {
            return Math.toDegrees(Math.atan2(-dx, dz));
        }

        /** How far a point lies to the side of this flight's line. */
        public double offLine(double px, double pz) {
            double length = Math.hypot(dx, dz);
            return Math.abs((px - x) * dz - (pz - z) * dx) / length;
        }
    }

    private EyeFlights() { }

    /** The angle between two flights' headings, 0 to 180 degrees. */
    public static double angle(Flight a, Flight b) {
        double dot = (a.dx() * b.dx() + a.dz() * b.dz()) / (Math.hypot(a.dx(), a.dz()) * Math.hypot(b.dx(), b.dz()));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }

    /**
     * Where the two flights' lines cross, as {x, z}; empty when they are too near parallel to trust, or when the
     * crossing lies behind a throw (the eyes flew away from it) or beyond any stronghold.
     */
    public static Optional<double[]> crossing(Flight a, Flight b) {
        double angle = angle(a, b);
        if (angle < MIN_ANGLE || angle > 180 - MIN_ANGLE) return Optional.empty();
        double cross = a.dx() * b.dz() - a.dz() * b.dx();
        double ex = b.x() - a.x(), ez = b.z() - a.z();
        double t = (ex * b.dz() - ez * b.dx()) / cross;
        double u = (ex * a.dz() - ez * a.dx()) / cross;
        double la = Math.hypot(a.dx(), a.dz()), lb = Math.hypot(b.dx(), b.dz());
        if (t * la < -MAX_BEHIND || u * lb < -MAX_BEHIND) return Optional.empty();
        double x = a.x() + t * a.dx(), z = a.z() + t * a.dz();
        if (Math.hypot(x, z) > MAX_RANGE) return Optional.empty();
        return Optional.of(new double[] {x, z});
    }

    /**
     * Where to throw the second eye from: {@code distance} blocks to one side of the first flight, square to its
     * heading, which gives the two lines a wide angle at the stronghold.
     */
    public static double[] secondThrow(Flight first, double distance) {
        double length = Math.hypot(first.dx(), first.dz());
        return new double[] {first.x() - first.dz() / length * distance, first.z() + first.dx() / length * distance};
    }
}
