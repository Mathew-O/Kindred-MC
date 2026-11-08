package dev.kindred.core.rescue;

import java.util.List;
import java.util.Objects;

/**
 * In plain terms: what kind of hole a stuck player is in, told coarsely enough that the same kind of hole anywhere looks
 * the same: walled in on which sides, how much room overhead, what is underfoot, water or lava near, whether what hems
 * it in is too hard for its tools, whether it is dark, whether there is a ledge it could climb to, whether it stands in a
 * shaft one wide, and whether it has blocks and a pickaxe. Never where it is.
 *
 * Technical: F-29d. Pure features of a {@link RescueSlice} as the player sees it and of its kit; two scenes match when
 * every feature agrees ({@link #key}). No coordinate, no block behind a wall, no count finer than the buckets below.
 */
public record RescueSignature(String walls, int headroom, RescueSlice.Kind under, boolean liquidAbove, boolean liquidBelow,
                              boolean liquidBeside, boolean hemmedHard, boolean dark, boolean ledge, boolean shaft,
                              boolean blocks, int pickaxe) {
    public RescueSignature {
        Objects.requireNonNull(walls);
        Objects.requireNonNull(under);
    }

    /** Most room overhead told apart; a pickaxe's tiers told apart (0 none, 1 wood or gold, 2 stone, 3 iron or better). */
    static final int HEADROOM = 4, PICKAXE = 3;

    /**
     * The scene's signature: {@code light} at its feet, whether it carries blocks it can place, and its best pickaxe's
     * tier (0 none to 5 netherite, as the goal's snapshot counts them).
     */
    public static RescueSignature of(RescueSlice slice, int light, boolean blocks, int pickaxeTier) {
        var sides = List.of(RescuePlan.Way.NORTH, RescuePlan.Way.SOUTH, RescuePlan.Way.EAST, RescuePlan.Way.WEST);
        var walls = new StringBuilder();
        boolean hard = false, shaft = true;
        for (var way : sides) {
            var reach = slice.walk(way, 2);
            boolean walled = reach.went() < 2 && !reach.stop().isEmpty();
            walls.append(walled ? way.name().charAt(0) : '-');
            if (reach.went() > 0) shaft = false;
            // What hems it in that way: the first cells of the walk's block that are solid.
            int[] s = RescueSlice.step(way);
            for (int dy = 0; dy <= 1; dy++) {
                var cell = slice.at(s[0], dy, s[1]);
                if (cell.kind() == RescueSlice.Kind.SOLID && !cell.breakable()) hard = true;
            }
        }
        int headroom = Math.min(HEADROOM, slice.up(HEADROOM).went());
        var under = slice.at(0, -1, 0).kind();
        boolean above = false, below = false, beside = false;
        for (int dy = -2; dy <= 3; dy++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dx = -2; dx <= 2; dx++) {
                    var kind = slice.at(dx, dy, dz).kind();
                    if (kind != RescueSlice.Kind.WATER && kind != RescueSlice.Kind.LAVA) continue;
                    if (dy >= 2) above = true;
                    else if (dy < 0) below = true;
                    else beside = true;
                }
        return new RescueSignature(walls.toString(), headroom, under, above, below, beside, hard, light < 8,
                ledge(slice), shaft && headroom >= 2, blocks, Math.min(PICKAXE, pickaxeTier >= 3 ? 3 : pickaxeTier));
    }

    /** A place to step off onto within a pillar's reach: beside the column over its head, open with ground under it. */
    private static boolean ledge(RescueSlice slice) {
        int clear = slice.up(6).went();
        for (var way : List.of(RescuePlan.Way.NORTH, RescuePlan.Way.SOUTH, RescuePlan.Way.EAST, RescuePlan.Way.WEST)) {
            int[] s = RescueSlice.step(way);
            for (int h = 1; h <= Math.min(6, clear); h++) {
                if (slice.at(s[0], h, s[1]).kind() == RescueSlice.Kind.OPEN && slice.at(s[0], h + 1, s[1]).kind() == RescueSlice.Kind.OPEN
                        && slice.at(s[0], h - 1, s[1]).kind() == RescueSlice.Kind.SOLID) return true;
            }
        }
        return false;
    }

    /** The signature as a key: two scenes match when their keys are equal. */
    public String key() {
        return "walls=" + walls + ";head=" + headroom + ";under=" + under.name().toLowerCase(java.util.Locale.ROOT)
                + ";liquid=" + (liquidAbove ? "a" : "") + (liquidBelow ? "b" : "") + (liquidBeside ? "s" : "")
                + ";hard=" + hemmedHard + ";dark=" + dark + ";ledge=" + ledge + ";shaft=" + shaft + ";blocks=" + blocks
                + ";pickaxe=" + pickaxe;
    }
}
