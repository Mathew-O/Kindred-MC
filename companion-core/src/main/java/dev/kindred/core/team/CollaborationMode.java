package dev.kindred.core.team;

/**
 * In plain terms: whether the AI players work as a team (sharing projects, chests and discoveries) or each
 * plays alone.
 *
 * Technical: Controls whether AI players coordinate via shared projects, stock reservations,
 * and shared discovery atlases, or operate as completely independent solo players.
 */
public enum CollaborationMode {
    /**
     * Bots share TeamCoordinator projects (E-11), reserve shared chest stock (StockLedger),
     * synchronize discoveries at the Base Lectern (INT-01), and perform PROTECT_ALLY peels (INT-02).
     * Even in COOP_TEAM, if teammates are offline/paused, any bot can claim and finish any task solo.
     */
    COOP_TEAM,

    /**
     * Every bot ignores TeamCoordinator shared tasks and shared chest reservations,
     * evaluates only its own personal MilestoneGraph and personal inventory/workstations,
     * and plays a 100% self-contained solo run.
     */
    SOLO_INDEPENDENT
}
