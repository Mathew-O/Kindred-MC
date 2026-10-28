package dev.kindred.core.progression;

/**
 * In plain terms: how much of the basics (logs, cobblestone, iron) to gather in one go before moving on, so a
 * player does not keep running back for one more block. The amounts depend on its playstyle.
 *
 * Technical: Lookahead horizon bill-of-materials batch targets for early survival milestones (EXEC-01).
 */
public final class HorizonBomCalculator {

    public enum HorizonResource {
        WOOD_LOGS,
        COBBLESTONE,
        IRON_INGOTS
    }

    /** How many to gather in one trip for this playstyle. */
    public static int targetCount(HorizonResource resource, PlaystyleProfile profile) {
        PlaystyleProfile effective = profile == null ? PlaystyleProfile.OPTIMAL_SURVIVAL : profile.materialQuotaProfile();
        return switch (resource) {
            case WOOD_LOGS -> effective.initialWoodLogsQuota();
            case COBBLESTONE -> effective.initialCobblestoneQuota();
            case IRON_INGOTS -> effective.ironIngotQuotaPerPlayer();
        };
    }

    /**
     * Returns how many more units the player should gather before leaving a resource patch.
     */
    public static int deficit(int ownedCount, HorizonResource resource, PlaystyleProfile profile) {
        return Math.max(0, targetCount(resource, profile) - Math.max(0, ownedCount));
    }

    /**
     * True when the player has satisfied the horizon batch for this resource at the current playstyle.
     */
    public static boolean batchSatisfied(int ownedCount, HorizonResource resource, PlaystyleProfile profile) {
        return deficit(ownedCount, resource, profile) == 0;
    }

    private HorizonBomCalculator() { }
}
