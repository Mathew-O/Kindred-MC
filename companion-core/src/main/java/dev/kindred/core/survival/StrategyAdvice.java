package dev.kindred.core.survival;

import dev.kindred.core.guide.Playbook;
import dev.kindred.core.guide.TaskQueue;
import dev.kindred.core.guide.Want;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Advice for an AI player's standing goal from an optional model strategist (F-19b, F-19c): a stance the local route
 * keeps for a while, places to keep away from, a focus on what to do next, and a note for the player's own memory,
 * which may carry a habit the player keeps in that world from then on (or retire one it kept), and follow-ups for the
 * player's queue: needs from a fixed set for the objectives ahead, gathered or crafted when the chance comes (F-19e),
 * and a way of the playbook to take for a part of the run, taken only where it can be done and is not much dearer than
 * the best (F-25c8). Advice is never an
 * order: the planner and the body's rules still decide every step, and the advice only shifts how careful they are
 * and which of the route's ordinary jobs comes first ({@link PlanPreferences}). Places must lie near something the
 * player itself reported (a death, a danger zone), so a model cannot point the player at hidden resources or
 * anywhere it has not been, and a habit needs a note saying why. Pure and immutable.
 *
 * @param stance how careful the route is for a while
 * @param avoid  places to keep away from, at most {@link #MAX_AVOID}
 * @param note   a short note the player keeps in its memory for this world, possibly empty
 * @param reason why, in a sentence, for the log and the memory
 * @param focus  what to do first for a while, where the route allows
 * @param habit  a lasting lesson the note carries, or {@code NONE}
 * @param retire a habit kept in this world that is no longer worth keeping, or {@code NONE}
 * @param queue  follow-ups for the player's queue, at most {@link TaskQueue#MAX_PER_ADVICE}
 * @param approach a way of the playbook advised for a part of the run, or null for none
 */
public record StrategyAdvice(Stance stance, List<Place> avoid, String note, String reason,
                             PlanPreferences.Focus focus, PlanPreferences.Habit habit, PlanPreferences.Habit retire, List<Want> queue,
                             Playbook.Approach approach) {
    /** How careful the route is. */
    public enum Stance {
        /** Carry on with the route as the local planner sees fit. */
        PRESS_ON,
        /** No caving; hole up and back off sooner; leave fights it is not sure to win. */
        PLAY_SAFE,
        /** Get to the surface, out of danger, and heal before the next job below ground. */
        SURFACE_AND_RECOVER
    }

    public record Place(int x, int y, int z) {
        double distanceTo(Place other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public static final int MAX_AVOID = 3;
    public static final int MAX_TEXT = 240;
    /** An avoided place must lie this close to a place the player reported. */
    public static final int REPORTED_RADIUS = 24;

    public StrategyAdvice {
        Objects.requireNonNull(stance);
        avoid = List.copyOf(avoid);
        note = Objects.requireNonNull(note);
        reason = Objects.requireNonNull(reason);
        Objects.requireNonNull(focus);
        Objects.requireNonNull(habit);
        Objects.requireNonNull(retire);
        queue = List.copyOf(queue.subList(0, Math.min(queue.size(), TaskQueue.MAX_PER_ADVICE)));
    }

    /** Advice with no way of the playbook advised. */
    public StrategyAdvice(Stance stance, List<Place> avoid, String note, String reason,
                          PlanPreferences.Focus focus, PlanPreferences.Habit habit, PlanPreferences.Habit retire, List<Want> queue) {
        this(stance, avoid, note, reason, focus, habit, retire, queue, null);
    }

    /** The way of the playbook advised, if any. */
    public Optional<Playbook.Approach> advised() { return Optional.ofNullable(approach); }

    /** Advice with nothing for the queue. */
    public StrategyAdvice(Stance stance, List<Place> avoid, String note, String reason,
                          PlanPreferences.Focus focus, PlanPreferences.Habit habit, PlanPreferences.Habit retire) {
        this(stance, avoid, note, reason, focus, habit, retire, List.of());
    }

    /** Advice with a stance only: no focus, no habit. */
    public StrategyAdvice(Stance stance, List<Place> avoid, String note, String reason) {
        this(stance, avoid, note, reason, PlanPreferences.Focus.NONE, PlanPreferences.Habit.NONE, PlanPreferences.Habit.NONE);
    }

    /** The same advice under another stance. */
    public StrategyAdvice withStance(Stance other) {
        return new StrategyAdvice(other, avoid, note, reason, focus, habit, retire, queue, approach);
    }

    /** {@link #validated(String, List, String, String, List, String, String, String)} with no focus or habit. */
    public static Optional<StrategyAdvice> validated(String stance, List<Place> avoid, String note, String reason, List<Place> reported) {
        return validated(stance, avoid, note, reason, reported, null, null, null);
    }

    /**
     * Advice from a model's raw reply, or nothing when the reply does not name a known stance. Places not near any
     * of {@code reported} are dropped, and text is trimmed to {@link #MAX_TEXT} plain characters. An unknown focus or
     * habit counts as none; so does a habit without a note to say why.
     */
    public static Optional<StrategyAdvice> validated(String stance, List<Place> avoid, String note, String reason, List<Place> reported,
                                                     String focus, String habit, String retire) {
        return validated(stance, avoid, note, reason, reported, focus, habit, retire, List.of());
    }

    /**
     * As {@link #validated(String, List, String, String, List, String, String, String)}, with follow-ups for the queue:
     * each already parsed from the fixed sets ({@link Want#parse}), one a need, the first of each kept, at most
     * {@link TaskQueue#MAX_PER_ADVICE}.
     */
    public static Optional<StrategyAdvice> validated(String stance, List<Place> avoid, String note, String reason, List<Place> reported,
                                                     String focus, String habit, String retire, List<Want> queue) {
        return validated(stance, avoid, note, reason, reported, focus, habit, retire, queue, null);
    }

    /**
     * As {@link #validated(String, List, String, String, List, String, String, String, List)}, with a way of the
     * playbook ({@link Playbook.Approach#parse}); an unknown way, or none, counts as none.
     */
    public static Optional<StrategyAdvice> validated(String stance, List<Place> avoid, String note, String reason, List<Place> reported,
                                                     String focus, String habit, String retire, List<Want> queue, String approach) {
        Optional<Stance> parsed = parse(Stance.class, stance);
        if (parsed.isEmpty()) return Optional.empty();
        var kept = new ArrayList<Place>();
        if (avoid != null) {
            for (Place place : avoid) {
                if (place == null || kept.size() >= MAX_AVOID) continue;
                if (reported.stream().anyMatch(known -> known.distanceTo(place) <= REPORTED_RADIUS)) kept.add(place);
            }
        }
        String plainNote = plain(note);
        var learned = plainNote.isEmpty() ? PlanPreferences.Habit.NONE : parse(PlanPreferences.Habit.class, habit).orElse(PlanPreferences.Habit.NONE);
        var wants = new ArrayList<Want>();
        if (queue != null) {
            for (Want want : queue) {
                if (want == null || wants.size() >= TaskQueue.MAX_PER_ADVICE) continue;
                if (wants.stream().noneMatch(w -> w.need() == want.need())) wants.add(want);
            }
        }
        return Optional.of(new StrategyAdvice(parsed.get(), kept, plainNote, plain(reason),
                parse(PlanPreferences.Focus.class, focus).orElse(PlanPreferences.Focus.NONE), learned,
                parse(PlanPreferences.Habit.class, retire).orElse(PlanPreferences.Habit.NONE), wants,
                Playbook.Approach.parse(approach).orElse(null)));
    }

    private static <E extends Enum<E>> Optional<E> parse(Class<E> type, String name) {
        if (name == null) return Optional.empty();
        try {
            return Optional.of(Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** Printable text on one line, at most {@link #MAX_TEXT} characters. */
    static String plain(String text) {
        if (text == null) return "";
        var out = new StringBuilder();
        for (int i = 0; i < text.length() && out.length() < MAX_TEXT; i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString().trim();
    }
}
