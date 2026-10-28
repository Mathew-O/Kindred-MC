package dev.kindred.core.tactics;

import dev.kindred.core.tactics.Melee.Move;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The strategist's combat proposals (E-41f, Milestone 5): advice on how to fight, never the fighting. The model is
 * offered the tactics that are valid for the fight as it stands, each a composition of the melee's own moves that has
 * been checked to make sense (a {@link Tactic}), and picks one with a reason; nothing else it could say is used. A
 * proposal only leans the hand-set melee's choice of move for a while ({@link #bias}, bounded), so the fight goes on
 * locally while the question is out and without any answer at all; it is checked again against the fight when it comes
 * back, adopted only if still valid, and dropped the moment it is not ({@link Gate}). One question at a time and
 * cooled down between (the strategist bridge also holds each player to one question at a time within its budgets).
 * Pure.
 */
public final class CombatProposals {
    private CombatProposals() { }

    /** The validated compositions a proposal may choose among. */
    public enum Tactic {
        /** Keep on top of it: close in and trade, the shield and stepping back given less weight. */
        PRESS,
        /** Let it come: the shield up for its swing and the strike after, closing in given less weight. */
        COUNTER,
        /** Strike and step out of its reach while the swing recovers. */
        HIT_AND_RUN,
        /** Leave the fight: get away, whatever the health. */
        DISENGAGE
    }

    /** The largest lean a proposal puts on any one move: never enough to open a move the melee would not make. */
    public static final double MAX_LEAN = 0.3;
    /** How long an adopted proposal leans the melee, in ticks, unless dropped sooner. */
    public static final int HOLD_TICKS = 200;
    /** Ticks between questions, at least. */
    public static final int COOLDOWN_TICKS = 200;

    /** The fight as it stands, as a proposal is checked against it. */
    public record Facts(double health, double opponentHealth, boolean shield, boolean food, FightOdds.Verdict odds, double distance) {
        public Facts {
            Objects.requireNonNull(odds);
        }
    }

    /** Whether {@code tactic} makes sense in the fight as {@code facts} have it. */
    public static boolean valid(Tactic tactic, Facts facts) {
        return switch (tactic) {
            case PRESS -> facts.odds() != FightOdds.Verdict.LOSING && facts.health() > Melee.LOW;
            case COUNTER -> facts.shield();
            case HIT_AND_RUN -> facts.health() > Melee.LOW;
            case DISENGAGE -> true;
        };
    }

    /** The tactics offered for {@code facts}: the valid ones. */
    public static List<Tactic> offered(Facts facts) {
        var tactics = new ArrayList<Tactic>();
        for (Tactic t : Tactic.values()) if (valid(t, facts)) tactics.add(t);
        return tactics;
    }

    /** The choices as the model is offered them. */
    public static List<String> choices(Facts facts) {
        return offered(facts).stream().map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
    }

    /** A model's choice read back, if it is one of the tactics. */
    public static Optional<Tactic> parse(String choice) {
        if (choice == null) return Optional.empty();
        try {
            return Optional.of(Tactic.valueOf(choice.strip().toUpperCase(Locale.ROOT).replace(' ', '_')));
        } catch (IllegalArgumentException notOne) {
            return Optional.empty();
        }
    }

    /** What the model is told it is for. */
    public static final String SYSTEM = "You advise an AI Minecraft player in a duel it joined, on how to fight, not on what to press: its own "
            + "controls keep fighting while you answer and do the fighting after. Pick one of the tactics offered, the one most likely to win "
            + "the duel from the facts given, and say why in a sentence. Answer only with one of the offered choices.";

    /** The question for {@code facts}. */
    public static String question(Facts facts) {
        return String.format(Locale.ROOT, "Duel facts: your health %.0f of 20; the opponent's, as you believe it, %.0f of 20; you %s a shield and %s food; "
                        + "the odds by kit and health are %s; it is %.1f blocks off. Tactics: press (close in and trade), counter (shield up for its swing, "
                        + "strike after), hit_and_run (strike, then step out of reach while your swing recovers), disengage (get away). Offered: %s.",
                facts.health(), facts.opponentHealth(), facts.shield() ? "have" : "have no", facts.food() ? "have" : "have no",
                facts.odds().name().toLowerCase(Locale.ROOT), facts.distance(), String.join(", ", choices(facts)));
    }

    /** The tactic it takes by its own rule, with no model or no usable answer: whatever the melee does unled (none). */
    public static Optional<Tactic> local(Facts facts) {
        return Optional.empty();
    }

    /**
     * How {@code tactic} leans the melee's moves: an amount added to a move's score where the move is open, at most
     * {@link #MAX_LEAN} either way; a getaway, opened outright.
     */
    public static Map<Move, Double> bias(Tactic tactic) {
        var b = new EnumMap<Move, Double>(Move.class);
        switch (tactic) {
            case PRESS -> {
                b.put(Move.APPROACH, 0.15);
                b.put(Move.STRIKE, 0.05);
                b.put(Move.BLOCK, -0.2);
                b.put(Move.BACK_OFF, -0.3);
                b.put(Move.STRAFE, -0.1);
            }
            case COUNTER -> {
                b.put(Move.BLOCK, 0.1);
                b.put(Move.STRAFE, 0.05);
                b.put(Move.APPROACH, -0.1);
            }
            case HIT_AND_RUN -> {
                b.put(Move.BACK_OFF, 0.3);
                b.put(Move.STRAFE, 0.05);
            }
            case DISENGAGE -> b.put(Move.RETREAT, 1.05);
        }
        return b;
    }

    /** {@code utilities} leaned by {@code tactic}: the moves open stay the moves open, but for a getaway. */
    public static Map<Move, Double> lean(Map<Move, Double> utilities, Tactic tactic) {
        var u = new EnumMap<Move, Double>(utilities);
        for (var each : bias(tactic).entrySet()) {
            if (each.getKey() == Move.RETREAT && tactic == Tactic.DISENGAGE) {
                u.put(Move.RETREAT, Math.max(u.getOrDefault(Move.RETREAT, 0.0), each.getValue()));
            } else if (u.containsKey(each.getKey())) {
                u.put(each.getKey(), u.get(each.getKey()) + Math.max(-MAX_LEAN, Math.min(MAX_LEAN, each.getValue())));
            }
        }
        return u;
    }

    /** A proposal adopted: its tactic, from when, until when, and the model's reason. */
    public record Proposal(Tactic tactic, long since, long until, String reason) { }

    /**
     * One player's proposals in a fight: a question only when none is out and the cooldown has passed; an answer
     * adopted only if its tactic is still valid; the adopted one dropped when it expires or stops being valid.
     */
    public static final class Gate {
        private boolean asking;
        private long askedAt = Long.MIN_VALUE / 2;
        private Proposal current;
        private String dropped = "";

        /** Whether a question may go out at {@code tick}. */
        public boolean mayAsk(long tick) {
            return !asking && tick - askedAt >= COOLDOWN_TICKS;
        }

        public void asked(long tick) {
            asking = true;
            askedAt = tick;
        }

        /** An answer back (or none): {@code tactic} adopted if it is still valid for {@code facts}. */
        public Optional<Proposal> answered(Optional<Tactic> tactic, String reason, Facts facts, long tick) {
            asking = false;
            if (tactic.isEmpty()) return Optional.empty();
            if (!valid(tactic.get(), facts)) {
                dropped = tactic.get().name().toLowerCase(Locale.ROOT) + " is no longer valid";
                return Optional.empty();
            }
            current = new Proposal(tactic.get(), tick, tick + HOLD_TICKS, reason == null ? "" : reason);
            return Optional.of(current);
        }

        /** The proposal leaning the melee at {@code tick} for {@code facts}, if any: dropped once expired or invalid. */
        public Optional<Proposal> current(Facts facts, long tick) {
            if (current != null && (tick >= current.until() || !valid(current.tactic(), facts))) {
                dropped = current.tactic().name().toLowerCase(Locale.ROOT) + (tick >= current.until() ? " ran its time" : " is no longer valid");
                current = null;
            }
            return Optional.ofNullable(current);
        }

        /** Why the last proposal was dropped, or "". */
        public String dropped() { return dropped; }

        public boolean asking() { return asking; }
    }
}
