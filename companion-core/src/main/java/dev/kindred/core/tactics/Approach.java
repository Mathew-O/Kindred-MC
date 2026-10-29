package dev.kindred.core.tactics;

import java.util.OptionalInt;

/**
 * In plain terms: how soon something flying, such as the Ender Dragon, will come within reach of a spot if it
 * keeps its course, so a player can get clear of its wings in time.
 *
 * Technical: When something flying comes within reach of a point if it keeps its velocity (E-39h): the Ender
 * Dragon closing on a player up a tower, whose wings fling anyone near its body. Re-reckoned every tick, so a
 * turning dragon is followed as it turns. Pure.
 */
public final class Approach {
    /**
     * How far from the dragon's body vanilla 1.21.1's wings fling a player: each wing's box sits about four and a half
     * blocks out from the body, and `EnderDragonEntity` widens it by four on each side before looking for anyone in it.
     */
    public static final double WING_REACH = 11;

    private Approach() { }

    /**
     * Ticks until a mover at {@code from}, moving {@code velocity} blocks a tick, is within {@code reach} of
     * {@code at}, looking {@code horizon} ticks ahead: zero when it is already, empty when it will not be (moving away,
     * passing wide, or too slow to arrive in time). Each point is {x, y, z}.
     */
    public static OptionalInt within(double[] at, double[] from, double[] velocity, double reach, int horizon) {
        if (at.length != 3 || from.length != 3 || velocity.length != 3) throw new IllegalArgumentException("Points are x, y, z");
        if (!(reach > 0) || horizon < 0) throw new IllegalArgumentException("A reach above zero and a horizon of zero or more");
        double rx = from[0] - at[0], ry = from[1] - at[1], rz = from[2] - at[2];
        double c = rx * rx + ry * ry + rz * rz - reach * reach;
        if (c <= 0) return OptionalInt.of(0);
        double a = velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2];
        if (a < 1e-9) return OptionalInt.empty();
        double b = 2 * (rx * velocity[0] + ry * velocity[1] + rz * velocity[2]);
        double disc = b * b - 4 * a * c;
        if (disc < 0) return OptionalInt.empty();
        double t = (-b - Math.sqrt(disc)) / (2 * a);
        if (t < 0 || t > horizon) return OptionalInt.empty();
        return OptionalInt.of((int) Math.ceil(t));
    }
}
