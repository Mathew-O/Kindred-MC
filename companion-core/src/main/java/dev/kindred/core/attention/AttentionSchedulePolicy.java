package dev.kindred.core.attention;

import java.util.Objects;

/**
 * In plain terms: how often each kind of background thinking runs in each mood (routine, alert, combat,
 * recovering), and the hard limits on how much thinking may happen per tick, so the AI never slows the server
 * down.
 *
 * Technical: Cadences, category eligibility and deterministic work budget limits for the shared attention
 * scheduler.
 */
public final class AttentionSchedulePolicy {
    public static final int DEFAULT_MAX_SLICES_PER_TICK = 6;
    public static final int DEFAULT_MAX_DETAILED_SCANS_PER_TICK = 2;
    public static final long DEFAULT_SOFT_TIME_LIMIT_NANOS = 2_000_000L; // 2 ms
    public static final int MAX_PLAYERS = 3;

    private final int maxSlicesPerTick;
    private final int maxDetailedScansPerTick;
    private final long softTimeLimitNanos;
    private final boolean learningReviewEnabled;

    public AttentionSchedulePolicy(int maxSlicesPerTick, int maxDetailedScansPerTick,
                                   long softTimeLimitNanos, boolean learningReviewEnabled) {
        if (maxSlicesPerTick < 0) throw new IllegalArgumentException("maxSlicesPerTick cannot be negative: " + maxSlicesPerTick);
        if (maxDetailedScansPerTick < 0) throw new IllegalArgumentException("maxDetailedScansPerTick cannot be negative: " + maxDetailedScansPerTick);
        if (softTimeLimitNanos < 0) throw new IllegalArgumentException("softTimeLimitNanos cannot be negative: " + softTimeLimitNanos);
        this.maxSlicesPerTick = maxSlicesPerTick;
        this.maxDetailedScansPerTick = maxDetailedScansPerTick;
        this.softTimeLimitNanos = softTimeLimitNanos;
        this.learningReviewEnabled = learningReviewEnabled;
    }

    /** The standard limits and timings. */
    public static AttentionSchedulePolicy defaultPolicy() {
        return new AttentionSchedulePolicy(
                DEFAULT_MAX_SLICES_PER_TICK,
                DEFAULT_MAX_DETAILED_SCANS_PER_TICK,
                DEFAULT_SOFT_TIME_LIMIT_NANOS,
                false // LEARNING_REVIEW is a disabled future extension point
        );
    }

    public int maxSlicesPerTick() { return maxSlicesPerTick; }
    public int maxDetailedScansPerTick() { return maxDetailedScansPerTick; }
    public long softTimeLimitNanos() { return softTimeLimitNanos; }
    public int maxPlayers() { return MAX_PLAYERS; }
    public boolean learningReviewEnabled() { return learningReviewEnabled; }

    /**
     * Cadence in ticks for a given attention state and work kind.
     * Returns -1 if the category is deferred or disabled in this state.
     */
    public int cadenceTicks(AttentionState state, WorkKind kind) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(kind, "kind");

        if (kind == WorkKind.LEARNING_REVIEW && !learningReviewEnabled) {
            return -1;
        }

        return switch (state) {
            case ROUTINE -> switch (kind) {
                case BROAD_THREAT -> 5;
                case EQUIPMENT_READINESS -> 20;
                case ROUTINE_GOALS -> 5;
                case RESOURCE_DISCOVERY -> 10;
                case SOCIAL_DELIBERATION -> 40;
                case LEARNING_REVIEW -> 100;
            };
            case ALERT -> switch (kind) {
                case BROAD_THREAT -> 2;
                case EQUIPMENT_READINESS -> 10;
                case ROUTINE_GOALS, RESOURCE_DISCOVERY, SOCIAL_DELIBERATION, LEARNING_REVIEW -> -1;
            };
            case COMBAT -> switch (kind) {
                case BROAD_THREAT -> 2;
                case EQUIPMENT_READINESS -> 10;
                case ROUTINE_GOALS, RESOURCE_DISCOVERY, SOCIAL_DELIBERATION, LEARNING_REVIEW -> -1;
            };
            case RECOVERY -> switch (kind) {
                case BROAD_THREAT -> 5;
                case EQUIPMENT_READINESS -> 5;
                case ROUTINE_GOALS -> 10;
                case RESOURCE_DISCOVERY -> 20;
                case SOCIAL_DELIBERATION -> -1;
                case LEARNING_REVIEW -> 20;
            };
        };
    }

    /**
     * Whether a category is eligible to be scheduled in the given attention state.
     */
    public boolean isEligible(AttentionState state, WorkKind kind) {
        return cadenceTicks(state, kind) > 0;
    }
}
