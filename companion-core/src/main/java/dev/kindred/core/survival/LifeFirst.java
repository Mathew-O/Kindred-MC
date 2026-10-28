package dev.kindred.core.survival;

import java.util.Optional;

/**
 * One weighted list for what a player does (F-25c9, the owner's note of 2026-10-23): what keeps it alive comes first,
 * an ongoing build near the bottom. Pure: the adapter says what the player knows of itself, and gives each need its
 * weight here against the build's.
 * <ul>
 *   <li><b>Sleep at night</b> in a bed of its own, which sets its spawn there.</li>
 *   <li><b>Food enough to last</b> when hunger is low: it gets food until a full bar and a reserve are in its body and
 *   its pack together, not just the next mouthful, then goes back to what it was doing.</li>
 * </ul>
 * Sleep comes before food: a player does not hunt in the dark, and hunger does not fall while it sleeps. What a running
 * goal does about food and the night it decides itself (the goal's planner stocks food, and hunts by day).
 */
public final class LifeFirst {
    /** A full hunger bar. */
    public static final int FULL = 20;
    /** Hunger at or below which getting food comes before the build: seven shanks of ten. */
    public static final int LOW_HUNGER = 14;
    /** Food carried beyond a full bar that is enough to last: a day's work, as four pieces of raw beef. */
    public static final int RESERVE = 12;

    /** The weights, as need scores in the project band: life above the build, the build near the bottom. */
    public static final double SLEEP = 88, FOOD = 84, BUILD = 70;

    public enum Need { SLEEP, FOOD }

    /**
     * What the player knows of itself.
     *
     * @param hunger      its food level, 0 to 20
     * @param foodCarried the food points of the safe food it carries
     * @param sleepTime   night, or a thunderstorm: when a bed can be slept in
     * @param ownBed      a bed of its own it can get to, free
     * @param gettingFood it is already out getting food, so it keeps on until it has enough
     * @param foodSought  it looked for food lately and found none, so the build goes on meanwhile
     */
    public record State(int hunger, int foodCarried, boolean sleepTime, boolean ownBed, boolean gettingFood, boolean foodSought) { }

    /** The need that comes first now, its weight, and why; none when nothing comes before the build. */
    public record Decision(Optional<Need> need, double weight, String why) {
        static final Decision NONE = new Decision(Optional.empty(), 0, "");
    }

    private LifeFirst() { }

    public static Decision next(State s) {
        if (s.sleepTime() && s.ownBed()) return new Decision(Optional.of(Need.SLEEP), SLEEP, "Night: to its own bed, to sleep and set its spawn there");
        if (!s.foodSought() && (s.gettingFood() || s.hunger() <= LOW_HUNGER) && !enough(s.hunger(), s.foodCarried())) {
            return new Decision(Optional.of(Need.FOOD), FOOD, "Hungry: food enough to last (" + (s.hunger() + s.foodCarried()) + " of "
                    + (FULL + RESERVE) + ", in its body and its pack)");
        }
        return Decision.NONE;
    }

    /** A full bar and the reserve, in its body and its pack together: eating moves food from one to the other. */
    public static boolean enough(int hunger, int foodCarried) {
        return hunger + foodCarried >= FULL + RESERVE;
    }
}
