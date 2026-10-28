package dev.kindred.core.progression;

/**
 * In plain terms: a player's playstyle: idle observing (waits and watches, the default), optimal survival
 * (plays a careful, complete game) or speedrun (fastest route). It sets how much to gather, whether to build
 * properly, farm, and so on; it can be switched at any safe moment.
 *
 * Technical: Runtime play doctrine for an AI player. Modes are never hard-locked; callers may switch
 * freely at safe checkpoints. {@link #IDLE_OBSERVING} is the default upon creation.
 */
public enum PlaystyleProfile {
    IDLE_OBSERVING(
            0,
            0,
            0,
            false,
            false,
            false,
            false,
            0,
            true
    ),
    OPTIMAL_SURVIVAL(
            5,
            19,
            24,
            true,
            true,
            false,
            false,
            12,
            false
    ),
    SPEEDRUN_ANY_PERCENT(
            3,
            11,
            4,
            false,
            false,
            true,
            true,
            14,
            false
    );

    private final int initialWoodLogsQuota;
    private final int initialCobblestoneQuota;
    private final int ironIngotQuotaPerPlayer;
    private final boolean buildPermanentArchitecture;
    private final boolean establishIrrigatedFarms;
    private final boolean reclaimWorkstationsAfterUse;
    private final boolean allowBucketCastPortalWithoutDiamonds;
    private final int targetEyesOfEnder;
    private final boolean idleObserving;

    PlaystyleProfile(
            int initialWoodLogsQuota,
            int initialCobblestoneQuota,
            int ironIngotQuotaPerPlayer,
            boolean buildPermanentArchitecture,
            boolean establishIrrigatedFarms,
            boolean reclaimWorkstationsAfterUse,
            boolean allowBucketCastPortalWithoutDiamonds,
            int targetEyesOfEnder,
            boolean idleObserving) {
        this.initialWoodLogsQuota = initialWoodLogsQuota;
        this.initialCobblestoneQuota = initialCobblestoneQuota;
        this.ironIngotQuotaPerPlayer = ironIngotQuotaPerPlayer;
        this.buildPermanentArchitecture = buildPermanentArchitecture;
        this.establishIrrigatedFarms = establishIrrigatedFarms;
        this.reclaimWorkstationsAfterUse = reclaimWorkstationsAfterUse;
        this.allowBucketCastPortalWithoutDiamonds = allowBucketCastPortalWithoutDiamonds;
        this.targetEyesOfEnder = targetEyesOfEnder;
        this.idleObserving = idleObserving;
    }

    /** True while the bot should observe, scavenge trinkets, and avoid progression actions. */
    public boolean isIdleObserving() {
        return idleObserving;
    }

    /** Material and progression quotas for batch gathering; idle mode uses optimal survival as a neutral fallback. */
    public PlaystyleProfile materialQuotaProfile() {
        return idleObserving ? OPTIMAL_SURVIVAL : this;
    }

    /** How many logs to gather at the start. */
    public int initialWoodLogsQuota() {
        return materialQuotaProfile().initialWoodLogsQuota;
    }

    /** How much cobblestone to gather at the start. */
    public int initialCobblestoneQuota() {
        return materialQuotaProfile().initialCobblestoneQuota;
    }

    /** How much iron each player aims for. */
    public int ironIngotQuotaPerPlayer() {
        return materialQuotaProfile().ironIngotQuotaPerPlayer;
    }

    /** Whether to build proper lasting buildings. */
    public boolean buildPermanentArchitecture() {
        return !idleObserving && buildPermanentArchitecture;
    }

    /** Whether to set up watered farms. */
    public boolean establishIrrigatedFarms() {
        return !idleObserving && establishIrrigatedFarms;
    }

    /** Whether to pick up crafting tables and furnaces again after using them. */
    public boolean reclaimWorkstationsAfterUse() {
        return !idleObserving && reclaimWorkstationsAfterUse;
    }

    /** Whether to make portal obsidian with lava and water instead of mining it with a diamond pickaxe. */
    public boolean allowBucketCastPortalWithoutDiamonds() {
        return !idleObserving && allowBucketCastPortalWithoutDiamonds;
    }

    /** How many eyes of ender to make. */
    public int targetEyesOfEnder() {
        return materialQuotaProfile().targetEyesOfEnder;
    }

    /** Whether speedrunners' tricks are allowed. */
    public boolean speedrunCommunityTechEnabled() {
        return this == SPEEDRUN_ANY_PERCENT;
    }
}
