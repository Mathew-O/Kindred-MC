package dev.kindred.core.tactics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: where to aim a bow or a thrown ender pearl so it reaches a target, following how the game
 * makes them fly and drop. It can choose a flat, quick shot or a high lob over a wall.
 *
 * Technical: Where to aim a bow or a thrown item, as vanilla 1.21.1 flies them (E-39b, E-39f): each tick the
 * projectile moves by its velocity, then its velocity is slowed by drag and pulled down by gravity. An arrow
 * from a full draw leaves at three blocks a tick with drag 0.99 and gravity 0.05; a thrown ender pearl (any
 * thrown item) at one and a half with drag 0.99 and gravity 0.03. Pure: the adapter supplies where the
 * projectile starts (a little below the eyes) and where the target is. Vanilla adds a small random spread to
 * every player's throw or shot (up to about a degree), so a solution is where the throw is aimed, not a
 * promise of where it lands to the inch.
 */
public final class Ballistics {
    /** Speed of an arrow from a full draw, in blocks a tick. */
    public static final double FULL_DRAW = 3.0;
    static final double DRAG = 0.99, GRAVITY = 0.05;
    /** Ticks a flight is followed for: past this it has fallen far below anything worth aiming at. */
    static final int FLIGHT_TICKS = 200;

    /** How a projectile flies: its launch speed, the gravity taken off its velocity each tick and the drag kept. */
    public record Projectile(double speed, double gravity, double drag) {
        public Projectile {
            if (!(speed > 0) || !(gravity >= 0) || !(drag > 0 && drag <= 1)) throw new IllegalArgumentException("A projectile needs speed, gravity and drag");
        }

        /** An arrow from a full draw. */
        public static final Projectile ARROW = new Projectile(FULL_DRAW, GRAVITY, DRAG);
        /** A thrown ender pearl. */
        public static final Projectile PEARL = new Projectile(1.5, 0.03, 0.99);
    }

    /** A solution: the pitch to loose at (vanilla's sign: negative looks up) and the ticks the flight takes to arrive. */
    public record Shot(float pitch, int ticks) { }

    private Ballistics() { }

    /** The height an arrow shot at a given angle and speed reaches at a given distance, and when. */
    static Optional<double[]> heightAt(double pitch, double speed, double distance) {
        return heightAt(pitch, new Projectile(speed, GRAVITY, DRAG), distance);
    }

    /**
     * The height a projectile launched at {@code pitch} (degrees, negative up) reaches when it has gone
     * {@code distance} blocks horizontally, and after how many ticks; empty if it falls away before getting there.
     */
    static Optional<double[]> heightAt(double pitch, Projectile p, double distance) {
        double radians = Math.toRadians(-pitch);
        double vx = Math.cos(radians) * p.speed(), vy = Math.sin(radians) * p.speed();
        double x = 0, y = 0;
        for (int tick = 1; tick <= FLIGHT_TICKS; tick++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= distance) {
                double t = vx <= 0 ? 0 : (distance - x) / vx;
                return Optional.of(new double[] {y + (ny - y) * t, tick - 1 + t});
            }
            x = nx;
            y = ny;
            vx *= p.drag();
            vy = vy * p.drag() - p.gravity();
            if (vx < 1e-3) return Optional.empty();
        }
        return Optional.empty();
    }

    /** As {@link #trajectory(double, Projectile, double)} for an arrow loosed at {@code speed}. */
    public static List<double[]> trajectory(double pitch, double speed, double distance) {
        return trajectory(pitch, new Projectile(speed, GRAVITY, DRAG), distance);
    }

    /**
     * The points a projectile launched at {@code pitch} passes through, tick by tick, as (horizontal distance, height)
     * from where it starts, until it has gone {@code distance} blocks: the arc to check for anything in its way. The
     * last point is where it meets that distance.
     */
    public static List<double[]> trajectory(double pitch, Projectile p, double distance) {
        var points = new ArrayList<double[]>();
        double radians = Math.toRadians(-pitch);
        double vx = Math.cos(radians) * p.speed(), vy = Math.sin(radians) * p.speed();
        double x = 0, y = 0;
        points.add(new double[] {0, 0});
        for (int tick = 1; tick <= FLIGHT_TICKS && vx > 1e-3; tick++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= distance) {
                double t = (distance - x) / vx;
                points.add(new double[] {distance, y + (ny - y) * t});
                return points;
            }
            x = nx;
            y = ny;
            points.add(new double[] {x, y});
            vx *= p.drag();
            vy = vy * p.drag() - p.gravity();
        }
        return points;
    }

    /** The low arc for an arrow loosed at {@code speed}: see {@link #aim(double, double, Projectile, boolean)}. */
    public static Optional<Shot> aim(double distance, double rise, double speed) {
        return aim(distance, rise, new Projectile(speed, GRAVITY, DRAG), false);
    }

    /**
     * The arc that meets a target {@code distance} blocks off horizontally and {@code rise} blocks above the start;
     * empty when it cannot be reached. The low arc flies flatter and arrives sooner, for a moving or far target; the
     * high arc comes down steeply, over a wall or through an opening above the target (a cage's broken roof).
     */
    public static Optional<Shot> aim(double distance, double rise, Projectile p, boolean high) {
        Objects.requireNonNull(p);
        if (!(distance > 0)) return Optional.empty();
        // The height at the target rises with pitch up to the best reach, and falls beyond it.
        double best = Double.NEGATIVE_INFINITY, bestPitch = 0;
        for (double pitch = 89; pitch >= -89; pitch -= 1) {
            var at = heightAt(pitch, p, distance);
            if (at.isEmpty()) continue;
            if (at.get()[0] > best) {
                best = at.get()[0];
                bestPitch = pitch;
            }
        }
        if (best < rise) return Optional.empty();
        // Between the best pitch and the far end (straight down for the low arc, straight up for the high one), the
        // height at the target runs one way: search for where it equals the rise.
        double near = bestPitch, far = high ? -89 : 89;
        var farAt = heightAt(far, p, distance);
        if (farAt.isPresent() && farAt.get()[0] >= rise) return Optional.of(new Shot((float) far, (int) Math.ceil(farAt.get()[1])));
        for (int i = 0; i < 60; i++) {
            double mid = (near + far) / 2;
            var at = heightAt(mid, p, distance);
            if (at.isEmpty() || at.get()[0] < rise) far = mid;
            else near = mid;
        }
        var at = heightAt(near, p, distance).orElseThrow();
        return Optional.of(new Shot((float) near, (int) Math.ceil(at[1])));
    }
}
