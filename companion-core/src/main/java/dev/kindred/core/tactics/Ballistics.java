package dev.kindred.core.tactics;

import java.util.Optional;

/**
 * Where to aim a bow, as vanilla 1.21.1 flies an arrow (E-39b): each tick the arrow moves by its velocity, then its
 * velocity is slowed by drag (0.99 in air) and pulled down by gravity (0.05). A full draw looses it at three blocks a
 * tick. Pure: the adapter supplies where the arrow starts (a little below the eyes) and where the target is.
 */
public final class Ballistics {
    /** Speed of an arrow from a full draw, in blocks a tick. */
    public static final double FULL_DRAW = 3.0;
    static final double DRAG = 0.99, GRAVITY = 0.05;
    /** Ticks an arrow is followed for: past this it has fallen far below anything worth aiming at. */
    static final int FLIGHT_TICKS = 200;

    /** A solution: the pitch to loose at (vanilla's sign: negative looks up) and the ticks the arrow takes to arrive. */
    public record Shot(float pitch, int ticks) { }

    private Ballistics() { }

    /**
     * The height an arrow loosed at {@code pitch} (degrees, negative up) and {@code speed} reaches when it has gone
     * {@code distance} blocks horizontally, and after how many ticks; empty if it falls away before getting there.
     */
    static Optional<double[]> heightAt(double pitch, double speed, double distance) {
        double radians = Math.toRadians(-pitch);
        double vx = Math.cos(radians) * speed, vy = Math.sin(radians) * speed;
        double x = 0, y = 0;
        for (int tick = 1; tick <= FLIGHT_TICKS; tick++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= distance) {
                double t = vx <= 0 ? 0 : (distance - x) / vx;
                return Optional.of(new double[] {y + (ny - y) * t, tick - 1 + t});
            }
            x = nx;
            y = ny;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (vx < 1e-3) return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * The points an arrow loosed at {@code pitch} and {@code speed} passes through, tick by tick, as (horizontal
     * distance, height) from where it starts, until it has gone {@code distance} blocks: the arc to check for anything in
     * its way. The last point is where it meets that distance.
     */
    public static java.util.List<double[]> trajectory(double pitch, double speed, double distance) {
        var points = new java.util.ArrayList<double[]>();
        double radians = Math.toRadians(-pitch);
        double vx = Math.cos(radians) * speed, vy = Math.sin(radians) * speed;
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
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
        }
        return points;
    }

    /**
     * The low arc that meets a target {@code distance} blocks off horizontally and {@code rise} blocks above the
     * arrow's start, at {@code speed}; empty when the bow cannot reach it. The low arc flies flatter and arrives sooner,
     * so it is the one a moving or far target is aimed with.
     */
    public static Optional<Shot> aim(double distance, double rise, double speed) {
        if (!(distance > 0) || !(speed > 0)) return Optional.empty();
        // The arc's height at the target rises with pitch until the best range is reached: search below that pitch.
        double best = Double.NEGATIVE_INFINITY, bestPitch = 0;
        for (double pitch = 0; pitch >= -89; pitch -= 1) {
            var at = heightAt(pitch, speed, distance);
            if (at.isEmpty()) continue;
            if (at.get()[0] > best) {
                best = at.get()[0];
                bestPitch = pitch;
            }
        }
        if (best < rise) return Optional.empty();
        double low = 89, high = bestPitch; // From straight down (pitch 89) to the highest reach.
        var lowAt = heightAt(low, speed, distance);
        if (lowAt.isPresent() && lowAt.get()[0] > rise) return Optional.of(new Shot((float) low, (int) Math.ceil(lowAt.get()[1])));
        for (int i = 0; i < 60; i++) {
            double mid = (low + high) / 2;
            var at = heightAt(mid, speed, distance);
            if (at.isEmpty() || at.get()[0] < rise) low = mid;
            else high = mid;
        }
        var at = heightAt(high, speed, distance).orElseThrow();
        return Optional.of(new Shot((float) high, (int) Math.ceil(at[1])));
    }
}
