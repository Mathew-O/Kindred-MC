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
 * @param bed              a bed carried, to set down by the End portal
 * @param spawnByPortal    its respawn point is already by the End portal (a bed it used there)
 */
public record EndFightSnapshot(boolean inEnd, boolean dragonKilled, int framesSeen, int framesFilled, int eyes, boolean portalOpen,
                               boolean onIsland, int crystals, int shootable, int caged,
                               boolean dragonAlive, boolean dragonPerched, boolean inBreath, boolean exitOpen,
                               boolean bow, int arrows, int blocks, int swordTier, float health,
                               boolean bed, boolean spawnByPortal) {
    /** Frame blocks in a full End portal ring. */
    public static final int RING = 12;

    /** A way into a cage it holds: blocks to pillar up beside it. */
    public boolean canOpenCage() {
        return blocks >= 16;
    }

    /** Eyes still wanted to fill the ring: one for each frame it has not seen filled. */
    public int eyesWanted() {
        return Math.max(0, RING - framesFilled);
    }
}
