package dev.kindred.core.match;

import dev.kindred.core.Personality;

import java.util.Objects;

/**
 * In plain terms: whether an AI player says yes when someone asks it to race or duel, and what it says. Not while it is
 * hurt or hungry, nor while it is somewhere it will not leave; not a duel against someone far better armed, unless it is
 * the fighting kind; otherwise as keen as its personality makes it. It always says why.
 *
 * Technical: E-52's acceptance by an AI player's own rule, pure: its personality (combativeness and sociability), its
 * state as the adapter reads it, and fairness. Its acceptance stands in for a controller's only for this one match; it
 * never grants anything else.
 */
public final class MatchChoice {
    private MatchChoice() { }

    /** A duel or a race. */
    public enum Kind { DUEL, RACE }

    /**
     * What the player is like now: its health and food (of 20), whether it is somewhere it will not leave (the Nether, the
     * End, a fight), and its kit and the other side's (armour points plus a weapon's tier, for a duel).
     */
    public record State(float health, int food, boolean committed, int kit, int otherKit) { }

    /** Its answer, and what it says. */
    public record Choice(boolean accepts, String words) { }

    /** How much better kitted the other may be before a duel is not fair. */
    public static final int FAIR_MARGIN = 6;

    public static Choice decide(Personality personality, State state, Kind kind) {
        Objects.requireNonNull(personality);
        Objects.requireNonNull(state);
        Objects.requireNonNull(kind);
        if (state.health() < 14) return new Choice(false, "Not now: I'm hurt.");
        if (state.food() < 10) return new Choice(false, "Not now: I'm hungry.");
        if (state.committed()) return new Choice(false, "Not now: I'm in the middle of something I can't leave.");
        if (kind == Kind.DUEL && state.otherKit() > state.kit() + FAIR_MARGIN && personality.combativeness() < 75) {
            return new Choice(false, "Not against that kit; you'd flatten me.");
        }
        int keen = kind == Kind.DUEL ? (personality.combativeness() + personality.sociability()) / 2
                : (personality.initiative() + personality.sociability()) / 2;
        if (keen < 30) return new Choice(false, "I'll sit this one out.");
        return new Choice(true, kind == Kind.DUEL ? "You're on." : "You're on. Race you!");
    }
}
