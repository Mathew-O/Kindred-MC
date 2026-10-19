package dev.kindred.core.progression;

/**
 * Runtime play doctrine for an AI player. Modes are never hard-locked; callers may switch
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

    public int initialWoodLogsQuota() {
        return materialQuotaProfile().initialWoodLogsQuota;
    }

    public int initialCobblestoneQuota() {
        return materialQuotaProfile().initialCobblestoneQuota;
    }

    public int ironIngotQuotaPerPlayer() {
        return materialQuotaProfile().ironIngotQuotaPerPlayer;
    }

    public boolean buildPermanentArchitecture() {
        return !idleObserving && buildPermanentArchitecture;
    }

    public boolean establishIrrigatedFarms() {
        return !idleObserving && establishIrrigatedFarms;
    }

    public boolean reclaimWorkstationsAfterUse() {
        return !idleObserving && reclaimWorkstationsAfterUse;
    }

    public boolean allowBucketCastPortalWithoutDiamonds() {
        return !idleObserving && allowBucketCastPortalWithoutDiamonds;
    }

    public int targetEyesOfEnder() {
        return materialQuotaProfile().targetEyesOfEnder;
    }

    public boolean speedrunCommunityTechEnabled() {
        return this == SPEEDRUN_ANY_PERCENT;
    }
}
