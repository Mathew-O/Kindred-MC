package dev.kindred.core;

import java.util.Objects;

/**
 * In plain terms: how an older companion decides about friendly duels: whether to accept or start a sparring
 * match, what to say, and how to answer a player who hits it first. Its personality decides how keen it is.
 * These duels are play-fighting, not real combat.
 *
 * Technical: Pure personality-weighted duel decision policy. No Minecraft types; no world access.
 *
 * <p>A duel is a consensual, bounded sparring exchange initiated by either a player or the companion.
 * It is not combat: the companion uses weakened attacks, it avoids finishing blows and it stops when
 * the exchange is over. Combativeness drives willingness; initiative drives proactive challenge;
 * caution reduces risk-taking; being mid-task suppresses acceptance.
 *
 * <p>Hit-back (a single unarmed counter-hit when a player attacks first) is always allowed unless the
 * companion is stopped, but its expression is shaped by personality.
 */
public final class DuelPolicy {

    /**
     * The outcome of a duel-challenge decision.
     *
     * @param accept     whether the companion is willing to duel right now
     * @param replyLine  the line the companion says (e.g. accept or decline phrase)
     */
    public record Decision(boolean accept, String replyLine) { }

    /** A companion's current busy state when evaluating a duel request. */
    public enum BusyState { IDLE, FOLLOWING, RUNNING_SKILL }

    private DuelPolicy() { }

    // ----- Acceptance -----

    /**
     * Decide whether the companion accepts a player-initiated duel challenge.
     *
     * <p>Base acceptance probability:
     * <ul>
     *   <li>combativeness ≥ 70 → 85 %
     *   <li>combativeness 40..69 → 50 %
     *   <li>combativeness &lt; 40 → 20 %
     * </ul>
     * Running a skill multiplies the probability by 0.3 (not abandoning important work lightly).
     * Following multiplies by 0.7.
     *
     * @param personality the companion's personality
     * @param busy        what the companion is currently doing
     * @param seed        a deterministic value in 0..99 drawn from the companion's variation counter
     * @return the decision
     */
    public static Decision evaluateChallenge(Personality personality, BusyState busy, int seed) {
        Objects.requireNonNull(personality);
        Objects.requireNonNull(busy);
        int combativeness = personality.combativeness();
        double baseProb;
        if (combativeness >= 70) baseProb = 0.85;
        else if (combativeness >= 40) baseProb = 0.50;
        else baseProb = 0.20;

        double multiplier = switch (busy) {
            case RUNNING_SKILL -> 0.3;
            case FOLLOWING -> 0.7;
            case IDLE -> 1.0;
        };

        // Humor nudges reply wording but not the decision itself.
        boolean accept = (seed / 100.0) < (baseProb * multiplier);

        String line;
        if (accept) {
            line = acceptLine(personality, busy);
        } else {
            line = declineLine(personality, busy);
        }
        return new Decision(accept, line);
    }

    /**
     * Decide whether the companion proactively challenges a nearby player.
     *
     * <p>Only called when the companion is idle or following. High combativeness and initiative raise
     * the probability. Caution suppresses it slightly.
     *
     * @param personality companion personality
     * @param busy        current busy state
     * @param seed        deterministic 0..99 seed
     * @return non-null decision; if not accepting, the companion stays quiet
     */
    public static Decision evaluateInitiate(Personality personality, BusyState busy, int seed) {
        Objects.requireNonNull(personality);
        Objects.requireNonNull(busy);
        if (busy == BusyState.RUNNING_SKILL) return new Decision(false, "");

        int combativeness = personality.combativeness();
        int initiative = personality.initiative();
        int caution = personality.caution();
        if (combativeness < 60 || initiative < 50) return new Decision(false, "");

        // Base = 8% chance per check when both are neutral (60/50). Scales up to ~30%.
        double prob = 0.04 + ((combativeness - 60) / 40.0 * 0.15) + ((initiative - 50) / 50.0 * 0.10)
                - (caution / 100.0 * 0.06);
        boolean initiate = (seed / 100.0) < prob;
        if (!initiate) return new Decision(false, "");
        String line = initiateLine(personality);
        return new Decision(true, line);
    }

    // ----- Hit-back -----

    /**
     * The unarmed counter-hit reply line when a player attacks the companion without a duel in progress.
     * Always allowed unless stopped; the caller decides actual world execution.
     */
    public static String hitBackLine(Personality personality) {
        Objects.requireNonNull(personality);
        int combativeness = personality.combativeness();
        int humor = personality.humor();
        if (combativeness >= 75) {
            return humor >= 65
                    ? "Was that supposed to hurt? Here, let me show you how it's done."
                    : "Don't start something you can't finish.";
        } else if (combativeness >= 40) {
            return humor >= 65
                    ? "Ow! I wasn't ready for that. Fair enough, though."
                    : "Hey! That wasn't necessary.";
        } else {
            return humor >= 65
                    ? "Ow! What did I do to deserve that?"
                    : "That hurt. Please don't do that.";
        }
    }

    // ----- Post-duel -----

    /**
     * The line the companion says after a duel ends.
     *
     * @param companionWon whether the companion ended at higher HP (approximate win)
     * @param personality  companion personality
     */
    public static String postDuelLine(boolean companionWon, Personality personality) {
        Objects.requireNonNull(personality);
        int combativeness = personality.combativeness();
        int humor = personality.humor();
        if (companionWon) {
            if (combativeness >= 70) return humor >= 65 ? "Hah! Good match. You're not bad." : "Good fight. Whenever you're ready for a rematch.";
            else return "That was fun! No hard feelings?";
        } else {
            if (combativeness >= 70) return humor >= 65 ? "Okay, you got me. This time." : "You're better than I expected.";
            else return humor >= 65 ? "I might need more practice." : "You're pretty good at this.";
        }
    }

    // ----- Private helpers -----

    private static String acceptLine(Personality personality, BusyState busy) {
        int combativeness = personality.combativeness();
        int humor = personality.humor();
        if (busy == BusyState.FOLLOWING) {
            return humor >= 65 ? "Sure, a quick duel sounds fun. Try to keep up!" : "Alright, let's do it.";
        }
        if (combativeness >= 70) {
            return humor >= 65 ? "A duel? Ha! I've been waiting for someone to ask." : "Challenge accepted. Don't hold back.";
        } else if (combativeness >= 40) {
            return humor >= 65 ? "Sure, why not? Just don't laugh if I win." : "Alright, I'm willing.";
        } else {
            return humor >= 65 ? "I'm probably going to regret this, but... okay." : "Alright, I'll try.";
        }
    }

    private static String declineLine(Personality personality, BusyState busy) {
        int combativeness = personality.combativeness();
        int humor = personality.humor();
        if (busy == BusyState.RUNNING_SKILL) {
            return humor >= 65 ? "I'm a bit busy right now. Maybe later?" : "I'm working right now; ask me again later.";
        }
        if (combativeness < 40) {
            return humor >= 65 ? "A duel? I'd rather not end up in the dirt today." : "I'd rather not. I'm not much of a fighter.";
        } else {
            return humor >= 65 ? "Not really feeling it today, honestly." : "Maybe another time.";
        }
    }

    private static String initiateLine(Personality personality) {
        int humor = personality.humor();
        return humor >= 65
                ? "Hey, you look like you could use a workout. Want to spar?"
                : "Care for a quick duel? Just friendly sparring.";
    }
}
