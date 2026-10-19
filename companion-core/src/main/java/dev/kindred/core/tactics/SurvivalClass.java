package dev.kindred.core.tactics;

/**
 * Categorical safety classification for tactical alternatives.
 * Self-preservation is a hard constraint: an option in a superior survival class
 * strictly outranks any option in an inferior survival class, regardless of property,
 * loot, kill rewards, or personality bias.
 */
public enum SurvivalClass {
    /**
     * Residual health comfortably exceeds safety reserves, no unaddressed imminent lethal
     * events exist, and the continuation position has a credible safety path.
     */
    SURVIVABLE(0),

    /**
     * No comfortably survivable sequence is available, but this legal action reduces
     * immediate danger compared to other alternatives.
     */
    EMERGENCY_ONLY(1),

    /**
     * Critical geometry, timing, or damage evidence is missing or stale.
     * Uncertainty is never assumed to be zero risk.
     */
    UNKNOWN(2),

    /**
     * Violates authority, permissions, physical mechanics, or safety preconditions.
     * Never eligible for selection.
     */
    INELIGIBLE(3);

    private final int rank;

    SurvivalClass(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean isBetterThan(SurvivalClass other) {
        return this.rank < other.rank;
    }
}
