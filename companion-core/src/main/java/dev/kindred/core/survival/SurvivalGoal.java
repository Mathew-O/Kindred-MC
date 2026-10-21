package dev.kindred.core.survival;

/**
 * The standing objective an AI player pursues on its own. A goal never grants authority: autonomous
 * work still needs the server's edit policy, paid activity and qualified skills.
 */
public enum SurvivalGoal {
    /** No standing objective; the player only reacts to needs and explicit requests. */
    NONE("No goal"),
    /** Earn the materials for a Nether portal from an empty inventory, then build and light it. */
    NETHER_PORTAL("Build a Nether portal"),
    /**
     * On from a lit Nether portal to the End portal: blaze rods and ender pearls from the Nether, eyes of ender, and
     * the stronghold found by following them. Earns the portal first if it has none.
     */
    STRONGHOLD("Find the End portal");

    private final String label;

    SurvivalGoal(String label) { this.label = label; }

    public String label() { return label; }

    public static SurvivalGoal parse(String raw) {
        if (raw == null) return NONE;
        for (SurvivalGoal goal : values()) {
            if (goal.name().equalsIgnoreCase(raw.trim())) return goal;
        }
        return NONE;
    }
}
