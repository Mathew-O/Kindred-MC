package dev.kindred.core.tactics;

/**
 * Ordered objectives within the surviving option set.
 * Lexicographically ordered: rank 1 strictly outranks rank 2, which strictly outranks rank 3,
 * which strictly outranks rank 4.
 */
public enum TacticalObjective {
    /**
     * Order 1: Incoming attacks; escape route about to close; teammate under immediate threat.
     */
    RESOLVE_IMMEDIATE_SERIOUS_HARM(1),

    /**
     * Order 2: Threatened occupied shelter, known essential supplies, or authorized project assets.
     */
    PREVENT_IRREVERSIBLE_MATERIAL_LOSS(2),

    /**
     * Order 3: Reduce remaining threat while conserving health, equipment, and ammunition.
     */
    END_ENCOUNTER_SAFELY(3),

    /**
     * Order 4: Authorized food acquisition, recovery, return to work.
     */
    FULFIL_SURVIVAL_OR_TEAM_TASKS(4);

    private final int rank;

    TacticalObjective(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean hasHigherPriorityThan(TacticalObjective other) {
        return this.rank < other.rank;
    }
}
