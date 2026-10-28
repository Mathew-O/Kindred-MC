package dev.kindred.core.tactics;

import java.util.EnumMap;
import java.util.Map;

/**
 * Competent melee against another player (E-41b, Milestone 1), one move at a time and never on a model: close in;
 * strike when the swing is full; between swings, strafe round it, or step out of its reach when it is about to swing
 * and there is no shield, or raise the shield; get away when the fight is being lost; eat when well out of reach and
 * hurt. Each move is scored by a fixed utility from what the player sees, and the move it is making is kept for a few
 * ticks unless another is better by a margin (commitment), so it does not twitch between two near-equal moves; a strike
 * and a getaway are taken at once. Pure and deterministic: the same sight gives the same move.
 */
public final class Melee {
    /** The moves of a melee fight. */
    public enum Move { APPROACH, STRIKE, STRAFE, BACK_OFF, BLOCK, RETREAT, EAT }

    /** A player's reach for a strike, to the hitbox (E-41a). */
    public static final double REACH = 3.0;
    /** A swing at least this far recovered is a full one (the ruleset's 0.2 + 0.8 c², and a critical needs more than 0.9). */
    public static final double FULL = 0.95;
    /** Health at or under which a fight it is losing is given up. */
    public static final double LOW = 6.0;
    /** Health under which it eats, well out of reach. */
    public static final double HURT = 12.0;
    /** Kept for at least this many ticks unless another move is better by {@link #MARGIN}. */
    public static final int COMMIT = 5;
    public static final double MARGIN = 0.1;

    /**
     * What the player sees, a tick at a time: how far the opponent is (to its hitbox), how far its own swing has
     * recovered (0 to 1), its own health and whether it holds food and a shield it can raise; of the opponent, whether
     * its swing is likely recovered (from the time since its last swing seen and the weapon it holds), whether it is
     * blocking, and the health it is believed to have; and whether it is in sight.
     */
    public record Sight(double distance, double charge, double health, boolean food, boolean shield,
                        boolean opponentReady, boolean opponentBlocking, double opponentHealth, boolean visible,
                        double blocks, double spams) {
        /**
         * With no tendencies known (E-41c off, or nothing seen yet): its blocking and its swinging uncharged each as
         * likely as not.
         */
        public Sight(double distance, double charge, double health, boolean food, boolean shield, boolean opponentReady,
                     boolean opponentBlocking, double opponentHealth, boolean visible) {
            this(distance, charge, health, food, shield, opponentReady, opponentBlocking, opponentHealth, visible, 0.5, 0.5);
        }
    }

    /** The move chosen and why, in words. */
    public record Choice(Move move, String why) { }

    private Move current;
    private long since;

    /** The scores of each move for {@code s}; a move not open is absent. */
    public static Map<Move, Double> utilities(Sight s) {
        var u = new EnumMap<Move, Double>(Move.class);
        boolean inReach = s.distance() <= REACH && s.visible();
        if (s.health() <= LOW && s.opponentHealth() > s.health()) u.put(Move.RETREAT, 1.0);
        if (s.food() && s.health() < HURT && s.distance() > REACH + 3) u.put(Move.EAT, 0.95);
        // One that tends to raise its shield as the swing comes full (E-41c) is struck the moment it is, before it does.
        if (inReach && s.charge() >= FULL) u.put(Move.STRIKE, s.opponentBlocking() ? 0.4 : s.blocks() > 0.65 ? 0.97 : 0.9);
        // Against one that swings uncharged, its weak blows are not worth the shield every time.
        if (s.shield() && s.distance() <= REACH + 1 && s.opponentReady() && s.charge() < FULL) u.put(Move.BLOCK, s.spams() > 0.65 ? 0.55 : 0.85);
        if (s.distance() <= REACH + 0.5 && s.charge() < 0.6) u.put(Move.BACK_OFF, s.opponentReady() && !s.shield() ? 0.75 : 0.3);
        if (s.distance() <= REACH + 1 && s.charge() < FULL) u.put(Move.STRAFE, 0.6);
        if (s.distance() > REACH || !s.visible()) {
            // Arriving with the swing recovered: from just out of reach, closed only once it nearly is.
            u.put(Move.APPROACH, s.distance() > REACH + 1 || s.charge() >= 0.8 || !s.visible() ? 0.8 : 0.5);
        }
        if (u.isEmpty()) u.put(Move.STRAFE, 0.1);
        return u;
    }

    /** The move to make at {@code tick} from {@code s}, kept a while once made. */
    public Choice next(Sight s, long tick) {
        var u = utilities(s);
        Move best = u.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        double top = u.get(best);
        Move move = best;
        // A strike or a getaway at once; else the move it is making, while it is still open and nearly as good.
        if (current != null && current != best && best != Move.STRIKE && best != Move.RETREAT && u.containsKey(current)
                && (tick - since < COMMIT || top < u.get(current) + MARGIN)) {
            move = current;
        }
        if (move != current) {
            current = move;
            since = tick;
        }
        return new Choice(move, why(move, s));
    }

    private static String why(Move move, Sight s) {
        return switch (move) {
            case APPROACH -> "close in";
            case STRIKE -> s.opponentBlocking() ? "strike at its shield" : "strike, the swing full";
            case STRAFE -> "strafe round it while the swing recovers";
            case BACK_OFF -> "step out of its reach while the swing recovers";
            case BLOCK -> "shield up: it is about to swing";
            case RETREAT -> "get away: the fight is being lost";
            case EAT -> "eat, well out of its reach";
        };
    }
}
