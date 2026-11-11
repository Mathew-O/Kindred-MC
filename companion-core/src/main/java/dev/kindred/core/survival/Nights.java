package dev.kindred.core.survival;

import java.util.Optional;

/**
 * In plain terms: how a player spends its nights (E-70a). It is home by dusk and asleep in its own bed at the first
 * moment a bed can be used. Too far from home, it sleeps in the bed it carries, in the sealed pocket it digs, and takes
 * the bed back in the morning. With no bed at all, it holes up as before and wants a bed first thing. When a person
 * lies down it goes to bed too, so the person's night is not held up. It stays up only for work that only the night
 * gives, and says so. Outside the Overworld there is no night, and it carries on.
 *
 * Technical: Pure. The adapter says where the player stands against its night ({@link State}) and is told what to do
 * ({@link Decision}): the {@link Plan} and its weight, the time of day it turns for home, whether a bed is wanted
 * urgently, and why. Times are the Overworld's time of day, 0 to 23,999; distances are blocks; pace is blocks a second.
 */
public final class Nights {
    /** The first and last time of day a bed can be slept in, without a storm (vanilla's own bounds). */
    public static final long SLEEP_FROM = 12_542, SLEEP_UNTIL = 23_460;
    /** The slack it leaves itself on the way home: half a minute, for a door, a detour or a mob on the path. */
    public static final long MARGIN = 600;
    /** The longest walk home it will start for the night: three minutes. Farther, home is out of reach for tonight. */
    public static final long LONGEST_WALK = 3_600;
    /** A walking pace when none is known yet, in blocks a second. */
    public static final double WALKING = 4.3;

    /** The weights, in the project band: a person's night just under safety, its own bed as {@link LifeFirst#SLEEP}. */
    public static final double WITH_A_PERSON = 96, BED = LifeFirst.SLEEP, HOLE_UP = 86, NIGHT_WORK = 80;

    public enum Plan {
        /** Nothing about the night comes first now. */
        NONE,
        /** Home to its own bed, and asleep there. */
        HOME,
        /** Its carried bed set down in its sealed pocket, slept in, and taken back in the morning. */
        CARRIED,
        /** No bed to sleep in: the hole-up it digs, bounded as it always was. */
        HOLE_UP,
        /** Up for work only the night gives, armoured and near home, and saying so. */
        NIGHT_WORK
    }

    /**
     * Where the player stands against its night.
     *
     * @param timeOfDay   the Overworld's time of day, 0 to 23,999
     * @param storm       a thunderstorm, in which a bed can be slept in by day
     * @param overworld   it is in the Overworld (in the Nether or the End there is no night to sleep through)
     * @param ownRules    a match it is in keeps its own rules for the night (a speedrun, a manhunt)
     * @param toHomeBed   blocks to its own home bed, or negative for none
     * @param pace        its walking pace, blocks a second, or 0 when not known yet
     * @param carriesBed  it carries a bed of its own
     * @param nightWork   a need in its queue is declared as night work
     * @param personAsleep a person in the Overworld is lying down now
     * @param canSleep    nothing keeps it from bed (no fight, not mid-task underground)
     */
    public record State(long timeOfDay, boolean storm, boolean overworld, boolean ownRules, double toHomeBed, double pace,
                        boolean carriesBed, boolean nightWork, boolean personAsleep, boolean canSleep) { }

    /**
     * What it does about the night.
     *
     * @param plan       what it does
     * @param weight     its weight against its work, or 0 for {@link Plan#NONE}
     * @param turnAt     the time of day it turns for home, or -1 when it has no home bed in reach
     * @param bedUrgent  a bed is wanted first thing (it has none, so a hole-up is its night)
     * @param why        why, in a line
     */
    public record Decision(Plan plan, double weight, long turnAt, boolean bedUrgent, String why) {
        static Decision none(long turnAt, String why) { return new Decision(Plan.NONE, 0, turnAt, false, why); }
    }

    private Nights() { }

    /** Whether a bed can be slept in at this time of day (vanilla: night, or any time in a storm). */
    public static boolean sleepTime(long timeOfDay, boolean storm) {
        long t = Math.floorMod(timeOfDay, 24_000L);
        return storm || t >= SLEEP_FROM && t <= SLEEP_UNTIL;
    }

    /** Ticks to walk {@code blocks} at {@code pace} blocks a second (or at {@link #WALKING} when no pace is known). */
    public static long walk(double blocks, double pace) {
        double p = pace > 0.1 ? pace : WALKING;
        return (long) Math.ceil(Math.max(0, blocks) / p * 20);
    }

    /**
     * The time of day it turns for home to be in bed when sleeping first becomes possible: the walk and the margin back
     * from {@link #SLEEP_FROM}; or -1 when home is out of reach for tonight (no home bed, or a walk longer than
     * {@link #LONGEST_WALK}).
     */
    public static long turnForHomeAt(double toHomeBed, double pace) {
        if (toHomeBed < 0) return -1;
        long walk = walk(toHomeBed, pace);
        if (walk > LONGEST_WALK) return -1;
        return Math.max(0, SLEEP_FROM - walk - MARGIN);
    }

    /** What it does about the night now. */
    public static Decision decide(State s) {
        long t = Math.floorMod(s.timeOfDay(), 24_000L);
        long turnAt = turnForHomeAt(s.toHomeBed(), s.pace());
        if (!s.overworld()) return Decision.none(-1, "No night here: it carries on");
        if (s.ownRules()) return Decision.none(turnAt, "Its match keeps its own rules for the night");
        boolean night = sleepTime(t, s.storm());
        boolean evening = turnAt >= 0 && t >= turnAt && t < SLEEP_FROM;
        if (s.personAsleep() && s.canSleep() && !s.nightWork()) {
            if (turnAt >= 0) return new Decision(Plan.HOME, WITH_A_PERSON, turnAt, false, "A person is going to bed: so does it, in its own bed");
            if (s.carriesBed()) return new Decision(Plan.CARRIED, WITH_A_PERSON, turnAt, false, "A person is going to bed: so does it, in the bed it carries");
        }
        if (!night && !evening) return Decision.none(turnAt, "Daytime");
        if (s.nightWork()) return new Decision(Plan.NIGHT_WORK, NIGHT_WORK, turnAt, false, "Up tonight for work only the night gives");
        if (turnAt >= 0) {
            return new Decision(Plan.HOME, BED, turnAt, false, night ? "Night: in its own bed" : "Dusk soon: home to its bed in time");
        }
        if (!night) return Decision.none(turnAt, "Home is out of reach tonight; it beds down where it is at dusk");
        if (s.carriesBed()) return new Decision(Plan.CARRIED, BED, turnAt, false, "Night, home out of reach: its own bed in its sealed pocket");
        return new Decision(Plan.HOLE_UP, HOLE_UP, turnAt, true, "Night, no bed to hand: holed up, and a bed wanted first thing");
    }

    /**
     * Whether an AI player is left out of the sleeping count (E-70a): one that cannot be in bed is never counted
     * against a person's night, unless the operator's {@code aiPlayersCountForSleep} counts them all.
     */
    public static boolean leftOutOfSleepCount(boolean aiPlayer, boolean inBed, boolean aiPlayersCount) {
        return aiPlayer && !inBed && !aiPlayersCount;
    }

    /** The need the night raises, if any: a bed, when it has none and holed up for the night. */
    public static Optional<dev.kindred.core.guide.Need> raises(Decision d) {
        return d.bedUrgent() ? Optional.of(dev.kindred.core.guide.Need.BED) : Optional.empty();
    }
}
