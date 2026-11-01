package dev.kindred.core.tactics;

/**
 * In plain terms: saving a long fall by pouring a water bucket where the player is about to land. It works
 * out how much the fall would hurt, whether it is worth saving, and the moment to pour.
 *
 * Technical: Breaking a fall with a water bucket (E-07d), as vanilla 1.21.1 reckons a fall: a landing hurts
 * one point for each block fallen past three (armour does not soften it), and a landing in water hurts
 * nothing, as the water is felt before the ground (`LivingEntity.fall` checks for water first). The water is
 * poured on the block the player will land on, from where its face is within reach, and as late as the fall
 * allows; it is worth the time only for a fall that would hurt. Pure.
 */
public final class FallClutch {
    /** How far a player reaches to use an item on a block face (vanilla's block interaction range). */
    public static final double REACH = 4.5;
    /** Eye height of a standing player. */
    public static final double EYE = 1.62;
    /** A fall worth breaking: one that would take this much health or more. */
    public static final int WORTH = 4;

    private FallClutch() { }

    /** What a fall of {@code blocks} in all would take, before Feather Falling: a point a block past three. */
    public static int damage(double blocks) {
        return Math.max(0, (int) Math.ceil(blocks - 3));
    }

    /**
     * Whether a fall already {@code fallen} blocks with {@code above} still to go to the ground is worth breaking, for a
     * player with {@code health}: one that would take {@link #WORTH} or more, or all but two of what it has.
     */
    public static boolean worth(double fallen, double above, double health) {
        int hurt = damage(fallen + above);
        return hurt >= WORTH || hurt > 0 && hurt >= health - 2;
    }

    /**
     * Whether to pour now, with the feet {@code above} blocks over the face it will land on: as soon as the face is
     * within reach of the eyes. Water poured early waits on the ground for the player falling into it, so the first
     * tick in reach is the one to take; at a speed of more than about three blocks a tick that tick can be skipped
     * altogether, as it can for any player.
     */
    public static boolean pourNow(double above) {
        return above >= 0 && above + EYE <= REACH - 0.05;
    }
}
