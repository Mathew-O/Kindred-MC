package dev.kindred.core.survival;

/**
 * What one AI player owns and has seen that matters from the End portal to the dragon's death and home again. Counts
 * are physical items it carries; sightings are things it can see itself (frame blocks, crystals on their pillars, the
 * dragon in flight or perched, the purple cloud of its breath). Nothing here is a world query.
 *
 * @param inEnd            the player is in the End
 * @param dragonKilled     it has seen the dragon die (kept by the leg across the trip home)
 * @param framesSeen       End portal frame blocks of its portal it has seen (twelve make the ring)
 * @param framesFilled     of those, the ones holding an eye
 * @param eyes             eyes of ender carried
 * @param portalOpen       the End portal's own blocks are there: the ring is full
 * @param onIsland         standing on the main island's end stone, within reach of the fight
 * @param crystals         End crystals still alive on their pillars, of those it has seen
 * @param shootable        of those, ones out of their bars it has not given up on for now (no clear shot found, or its
 *                         arrows missing)
 * @param caged            of those, ones inside their iron bars it has not given up on
 * @param dragonAlive      the dragon is alive (or not yet seen dead)
 * @param dragonPerched    the dragon sits on the exit portal's pillar, its head low enough to strike
 * @param inBreath         the player stands in or by the dragon's breath
 * @param exitOpen         the exit portal's own blocks are there: the dragon is dead
 * @param bow              a bow carried
 * @param arrows           arrows carried
 * @param blocks           blocks for pillaring
 * @param swordTier        best sword: 0 none, 1 wood/gold, 2 stone, 3 iron, 4 diamond
 * @param health           health 0..20
 * @param pearls           ender pearls carried (a caged crystal's roof is reached by one and left by another)
 */
public record EndFightSnapshot(boolean inEnd, boolean dragonKilled, int framesSeen, int framesFilled, int eyes, boolean portalOpen,
                               boolean onIsland, int crystals, int shootable, int caged,
                               boolean dragonAlive, boolean dragonPerched, boolean inBreath, boolean exitOpen,
                               boolean bow, int arrows, int blocks, int swordTier, float health, int pearls) {
    /** Frame blocks in a full End portal ring. */
    public static final int RING = 12;

    /** A way into a cage it holds: blocks to pillar up beside it, or two pearls to go up onto its roof and back down. */
    public boolean canOpenCage() {
        return blocks >= 16 || pearls >= 2;
    }

    /** Eyes still wanted to fill the ring: one for each frame it has not seen filled. */
    public int eyesWanted() {
        return Math.max(0, RING - framesFilled);
    }
}
