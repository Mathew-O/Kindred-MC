package dev.kindred.core.survival;

/**
 * What one AI player owns and has seen that matters on the way from a lit Nether portal to the End portal. Counts
 * are physical items in its own inventory; the sightings are things it has seen itself (blocks in its line of sight,
 * mobs in view, the flight of an eye of ender it threw). Nothing here is a world query.
 *
 * @param dimension         where the player is
 * @param portalKnown       the way between the dimensions it came by: in the Overworld its own lit portal, in the
 *                          Nether the portal it arrived through
 * @param edibleFood        food items safe to eat
 * @param foodLevel         hunger points 0..20
 * @param swordTier         best sword: 0 none, 1 wood/gold, 2 stone, 3 iron, 4 diamond
 * @param pickaxeTier       best pickaxe on the same scale
 * @param pickaxes          pickaxes carried that can mine stone
 * @param armorPieces       iron (or better) armor pieces worn or carried
 * @param shields           shields held or carried
 * @param blocks            plain blocks carried for bridging, pillaring and walling off (cobblestone, netherrack)
 * @param ironIngots        iron ingots carried
 * @param blazeRods         blaze rods
 * @param blazePowder       blaze powder
 * @param enderPearls       ender pearls
 * @param eyes              eyes of ender
 * @param fortressSeen      Nether bricks of a fortress seen
 * @param blazeSeen         a blaze (or its spawner) seen within reach of a walk
 * @param endermanSeen      an enderman seen within reach of a walk
 * @param pearlHuntStalled  the hunt for pearls has gone on a long time without one; with enough for the search it
 *                          moves on and takes pearls from endermen it meets on the way
 * @param throwsRecorded    eye-of-ender flights it has watched and recorded (each from a different place)
 * @param strongholdEstimate the flights of two throws cross: where the stronghold should be is known
 * @param nearEstimate      it stands within a few chunks of that place
 * @param strongholdSeen    stone bricks of the stronghold seen
 * @param portalRoomSeen    an End portal frame seen
 * @param framesFilled      End portal frame blocks seen holding an eye
 * @param boats             boats carried, for crossing water
 * @param planks            planks carried
 * @param logs              logs carried (four planks each)
 */
public record StrongholdRunSnapshot(
        Dimension dimension, boolean portalKnown, int edibleFood, int foodLevel,
        int swordTier, int pickaxeTier, int pickaxes, int armorPieces, int shields, int blocks, int ironIngots,
        int blazeRods, int blazePowder, int enderPearls, int eyes,
        boolean fortressSeen, boolean blazeSeen, boolean endermanSeen, boolean pearlHuntStalled,
        int throwsRecorded, boolean strongholdEstimate, boolean nearEstimate, boolean strongholdSeen,
        boolean portalRoomSeen, int framesFilled, int boats, int planks, int logs) {

    public enum Dimension { OVERWORLD, NETHER, END }

    /** Eyes it has or can make now from what it carries: one per pearl, two per rod or powder. */
    public int eyesPossible() {
        return eyes + Math.min(enderPearls, blazePowder + 2 * blazeRods);
    }
}
