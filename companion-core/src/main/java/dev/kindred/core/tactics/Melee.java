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
public final class Melee implements MeleePolicy {
    /** The moves of a melee fight. */
    public enum Move { APPROACH, STRIKE, STRAFE, BACK_OFF, BLOCK, RETREAT, EAT }

    /** A player's reach for a strike, to the hitbox (E-41a). */
    public static final double REACH = 3.0;
    /** A swing at least this far recovered is a full one (the ruleset's 0.2 + 0.8 c², and a critical needs more than 0.9). */
    public static final double FULL = 0.95;
    /**
     * A swing this far recovered, in reach, is wound up for: the jump for a critical begins, so the blow lands full on
     * the way down rather than a jump's length after the swing is full.
     */
    public static final double WIND_UP = 0.5;
    /** Health at or under which a fight it is losing is given up. */
    public static final double LOW = 6.0;
    /** Health under which it eats, well out of reach. */
    public static final double HURT = 12.0;
    /** Kept for at least this many ticks unless another move is better by {@link #MARGIN}. */
    public static final int COMMIT = 5;
    public static final double MARGIN = 0.1;
    /** The longest it waits behind its shield, its swing full, for a swing that does not come before it strikes anyway. */
    public static final int PATIENCE = 10;

    /**
     * What the player sees, a tick at a time: how far the opponent is (to its hitbox), how far its own swing has
     * recovered (0 to 1), its own health and whether it holds food and a shield it can raise; of the opponent, whether
     * its swing is likely recovered (from the time since its last swing seen and the weapon it holds), whether it is
     * blocking, and the health it is believed to have; and whether it is in sight.
     */
    public record Sight(double distance, double charge, double health, boolean food, boolean shield,
                        boolean opponentReady, boolean opponentBlocking, double opponentHealth, boolean visible,
                        double blocks, double spams, boolean axe) {
        /** Holding no axe. */
        public Sight(double distance, double charge, double health, boolean food, boolean shield, boolean opponentReady,
                     boolean opponentBlocking, double opponentHealth, boolean visible, double blocks, double spams) {
            this(distance, charge, health, food, shield, opponentReady, opponentBlocking, opponentHealth, visible, blocks, spams, false);
        }

        /**
         * With no tendencies known (E-41c off, or nothing seen yet): its blocking and its swinging uncharged each as
         * likely as not.
         */
        public Sight(double distance, double charge, double health, boolean food, boolean shield, boolean opponentReady,
                     boolean opponentBlocking, double opponentHealth, boolean visible) {
            this(distance, charge, health, food, shield, opponentReady, opponentBlocking, opponentHealth, visible, 0.5, 0.5, false);
        }
    }

    /** The move chosen and why, in words. */
    public record Choice(Move move, String why) { }

    private Move current;
    private long since;
    /** When it last gave up waiting behind its shield. */
    private long gaveUpAt = Long.MIN_VALUE / 2;
    /** A strategist's proposal leaning the moves (E-41f), or null. */
    private CombatProposals.Tactic lean;

    /** The moves leaned by {@code tactic} from now, or not at all with null (E-41f). */
    @Override
    public void lean(CombatProposals.Tactic tactic) {
        this.lean = tactic;
    }

    /** The scores of each move for {@code s}; a move not open is absent. */
    public static Map<Move, Double> utilities(Sight s) {
        var u = new EnumMap<Move, Double>(Move.class);
        boolean inReach = s.distance() <= REACH && s.visible();
        if (s.health() <= LOW && s.opponentHealth() > s.health()) u.put(Move.RETREAT, 1.0);
        if (s.food() && s.health() < HURT && s.distance() > REACH + 3) u.put(Move.EAT, 0.95);
        // One that tends to raise its shield as the swing comes full (E-41c) is struck the moment it is, before it does;
        // a raised shield is struck with an axe, which disables it, and with anything else only for want of a better move.
        // With a shield and the opponent's swing about to come, its swing is taken on the shield first and the strike
        // made while it recovers (a block, then the hit).
        // Ready to swing, it is a threat behind its shield as much as without: a shield comes down and the swing is made
        // in the same tick (and one raised again guards only after five ticks, the moment to strike back).
        boolean threat = s.opponentReady();
        if (inReach && s.charge() >= FULL) {
            u.put(Move.STRIKE, s.opponentBlocking() ? s.axe() ? 0.95 : 0.4 : s.shield() && threat ? 0.8 : s.blocks() > 0.65 ? 0.97 : 0.9);
        }
        if (inReach && s.charge() >= WIND_UP && s.charge() < FULL && !s.opponentBlocking()) u.put(Move.STRIKE, 0.7);
        // Against one that swings uncharged, its weak blows are not worth the shield every time.
        if (s.shield() && s.distance() <= REACH + 1 && threat) u.put(Move.BLOCK, s.spams() > 0.65 ? 0.55 : 0.85);
        // With no shield, the opponent's swing about to come and its own not back: out of its reach, so the swing is spent on
        // air, and in again for its own (spacing) rather than into the trade.
        if (s.distance() <= REACH + 0.5 && s.charge() < FULL) {
            if (s.opponentReady() && !s.shield()) u.put(Move.BACK_OFF, 0.75);
            else if (s.charge() < 0.6) u.put(Move.BACK_OFF, 0.3);
        }
        if (s.distance() <= REACH + 1 && s.charge() < FULL) u.put(Move.STRAFE, 0.6);
        if (s.distance() > REACH || !s.visible()) {
            // Arriving with the swing recovered: from just out of reach, closed only once it nearly is.
            u.put(Move.APPROACH, s.distance() > REACH + 1 || s.charge() >= 0.8 || !s.visible() ? 0.8 : 0.5);
        }
        if (u.isEmpty()) u.put(Move.STRAFE, 0.1);
        return u;
    }

    /** The move to make at {@code tick} from {@code s}, kept a while once made. */
    @Override
    public Choice next(Sight s, long tick) {
        var u = lean == null ? utilities(s) : CombatProposals.lean(utilities(s), lean);
        Move best = u.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        // Waited long enough behind the shield for a swing that has not come: the best of the rest (the strike, its swing
        // full and in reach; else closing in), so a player that does not swing is not waited on for ever.
        boolean impatient = best == Move.BLOCK && current == Move.BLOCK && tick - since >= PATIENCE && u.size() > 1;
        if (impatient) {
            best = u.entrySet().stream().filter(e -> e.getKey() != Move.BLOCK).max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            gaveUpAt = tick;
        }
        // A strike under way is finished (a jump for a critical swings on the way down); having given up on a swing that
        // did not come, the shield waits its turn again for a while.
        boolean shieldAtOnce = best == Move.BLOCK && current != Move.STRIKE && tick - gaveUpAt >= PATIENCE;
        double top = u.get(best);
        Move move = best;
        // A strike, the shield (it guards only after five ticks up, so a late one is no shield) or a getaway at once;
        // else the move it is making, while it is still open and nearly as good.
        if (!impatient && current != null && current != best && best != Move.STRIKE && !shieldAtOnce && best != Move.RETREAT && u.containsKey(current)
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
            case STRIKE -> s.charge() < FULL ? "jump for a critical as the swing recovers"
                    : s.opponentBlocking() ? s.axe() ? "the axe at its shield, to disable it" : "strike at its shield" : "strike, the swing full";
            case STRAFE -> "strafe round it while the swing recovers";
            case BACK_OFF -> "step out of its reach while the swing recovers";
            case BLOCK -> "shield up: it is about to swing";
            case RETREAT -> "get away: the fight is being lost";
            case EAT -> "eat, well out of its reach";
        };
    }
}
