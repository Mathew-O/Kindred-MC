package dev.kindred.core.survival;

/**
 * In plain terms: the goal a player works toward. Its own, when it has been given one (an explicit "none" included);
 * otherwise, in a faction world, the survival progression, which is what playing the mode asks of it: the Ender Dragon
 * where the server allows the fight, else the End portal. Nowhere else does a player without a goal get one.
 *
 * Technical: Pure and computed, never stored (the saved goal is the player's own). A goal needs the server's world
 * edits, as a goal set by a command does; without them a player keeps its own goal (none). The dragon is chosen by the
 * same policy the command checks ({@code dragonFight}). Leaving Factions mode returns a player to its own goal at once.
 */
public final class StandingGoal {
    private StandingGoal() { }

    /**
     * The goal {@code own} (its own; {@code chosen} when one was set for it, "none" included) stands for, in a faction
     * world or not, with world edits on or off and the dragon fight allowed or not.
     */
    public static SurvivalGoal of(SurvivalGoal own, boolean chosen, boolean factionWorld, boolean editsAllowed, boolean dragonAllowed) {
        SurvivalGoal mine = own == null ? SurvivalGoal.NONE : own;
        if (mine != SurvivalGoal.NONE || chosen || !factionWorld || !editsAllowed) return mine;
        return dragonAllowed ? SurvivalGoal.DRAGON : SurvivalGoal.STRONGHOLD;
    }
}
