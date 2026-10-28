package dev.kindred.core.progression;

/**
 * In plain terms: fair-play helpers that speedrunners use: where buried treasure and stronghold staircases
 * sit inside their chunk, and turning string into wool for a bed. Only active in speedrun mode with the
 * switch on.
 *
 * Technical: Pure coordinate math and recipe helpers for fair-play speedrunning community tech (SPEED-01).
 */
public final class SpeedrunTechPolicy {

    /**
     * In Java Edition 1.21.1, Buried Treasure chests always generate at chunk-local (9, ~, 9).
     */
    public static int[] buriedTreasureBlockXZ(int chunkX, int chunkZ) {
        return new int[]{(chunkX << 4) + 9, (chunkZ << 4) + 9};
    }

    /**
     * In Java Edition 1.21.1, Stronghold starter staircases originate at chunk-local (4, ~, 4).
     */
    public static int[] strongholdStaircaseBlockXZ(int chunkX, int chunkZ) {
        return new int[]{(chunkX << 4) + 4, (chunkZ << 4) + 4};
    }

    /**
     * Checks how much wool can be synthesized for a Bed using String (4 String = 1 Wool).
     */
    public static int craftableWoolFromString(int currentWoolCount, int currentStringCount) {
        int neededWool = Math.max(0, 3 - currentWoolCount);
        int craftable = currentStringCount / 4;
        return Math.min(neededWool, craftable);
    }

    /**
     * Community tech helpers are only active when speedrun mode and the runtime toggle allow them.
     */
    public static boolean communityTechAllowed(PlaystyleProfile profile, boolean speedrunCommunityTechEnabled) {
        if (profile == null || profile.isIdleObserving()) {
            return false;
        }
        return profile == PlaystyleProfile.SPEEDRUN_ANY_PERCENT && speedrunCommunityTechEnabled;
    }

    private SpeedrunTechPolicy() { }
}
