package dev.kindred.core.outing;

import dev.kindred.core.Personality;

/**
 * In plain terms: whether an AI player, invited on a trip by another, says yes: only if it has nothing of its own to do
 * (no goal, no task in hand), is not in danger or a match, and is sociable enough to want company; never because it was
 * told to. Saying no costs nothing.
 *
 * Technical: E-27a's rule for an AI invitee, its own choice as {@code match.MatchChoice} is for matches. A pure function of
 * what it is doing and its personality's bounded weights.
 */
public final class OutingChoice {
    private OutingChoice() { }

    /** How sociable it must be to go. */
    public static final int SOCIABLE = 40;

    public record Answer(boolean yes, String why) { }

    public static Answer answer(boolean hasGoal, boolean busy, boolean inDanger, boolean inMatch, Personality personality) {
        if (inMatch) return new Answer(false, "in a match");
        if (inDanger) return new Answer(false, "in danger");
        if (hasGoal) return new Answer(false, "has its own goal");
        if (busy) return new Answer(false, "busy");
        if (personality == null || personality.sociability() < SOCIABLE) return new Answer(false, "not one for company");
        return new Answer(true, "nothing else on");
    }
}
