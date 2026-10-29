package dev.kindred.core.survival;

/**
 * In plain terms: the big goal a player works toward on its own: none, a Nether portal, the End portal, or
 * beating the Ender Dragon. Having a goal never lets the player do anything the server does not allow.
 *
 * Technical: The standing objective an AI player pursues on its own. A goal never grants authority:
 * autonomous work still needs the server's edit policy, paid activity and qualified skills.
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
    STRONGHOLD("Find the End portal"),
    /**
     * Beat the game: on from the End portal into the End, the End crystals destroyed and the Ender Dragon killed, then
     * home through the exit portal. Earns everything before it first. Only where the server's policy allows the fight.
     */
    DRAGON("Beat the Ender Dragon");

    private final String label;

    SurvivalGoal(String label) { this.label = label; }

    public String label() { return label; }

    /** The goal a saved or typed name stands for, or no goal when it names none. */
    public static SurvivalGoal parse(String raw) {
        if (raw == null) return NONE;
        for (SurvivalGoal goal : values()) {
            if (goal.name().equalsIgnoreCase(raw.trim())) return goal;
        }
        return NONE;
    }
}
