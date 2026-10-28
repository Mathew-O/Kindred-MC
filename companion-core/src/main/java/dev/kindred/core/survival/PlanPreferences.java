package dev.kindred.core.survival;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Preferences the goal's task choice keeps to, beyond the route itself (F-19c): habits learned in this world, each
 * kept with the note that explains it, and a short focus on what to do next. They come from the optional strategist
 * but are plain data here, from fixed sets; the planner and the goal read them and nothing else. They change the
 * order and amount of ordinary work only: no step is skipped that the route needs, no item is given, and the
 * survival checks (fights, shelter, healing) always come first. The playbook's mode (normal play or speedrun) and a
 * way it was advised to take choose between the ways of doing a part of the run (F-25c8).
 */
public record PlanPreferences(Set<Habit> habits, Focus focus, dev.kindred.core.guide.Playbook.Mode mode,
                              dev.kindred.core.guide.Playbook.Approach advised) {
    /** Lessons kept for a world, each tied to the note it came with. */
    public enum Habit {
        NONE,
        /** Carry {@link #EXTRA_FOOD} more food than usual. */
        MORE_FOOD,
        /** Pack a second spare pickaxe for the work below ground. */
        SPARE_PICKAXE,
        /** Dig its own way for ore rather than explore caves. */
        NO_CAVES,
        /** Dig in for the night at dusk rather than at nightfall. */
        SHELTER_EARLY
    }

    /** What to do next, for a few minutes, where the route allows it. */
    public enum Focus {
        NONE,
        /** Hunt first, and look for animals in daylight when none is in sight, until well stocked. */
        FOOD,
        /** Gather logs first, until there is wood to spare for sticks, a table and fuel. */
        WOOD
    }

    /** Food carried beyond the usual stock when the player has learned it needs more. */
    public static final int EXTRA_FOOD = 3;
    /** Food a food focus wants beyond the usual stock. */
    public static final int FOCUS_FOOD = 3;
    /** Planks (a log is four) a wood focus wants beyond the wood already kept. */
    public static final int FOCUS_WOOD = 16;
    /** Distinct habits kept at once in a world: the newest win. */
    public static final int MAX_HABITS = 4;

    public static final PlanPreferences NONE = new PlanPreferences(Set.of(), Focus.NONE);

    /** Habits and a focus in normal play, with no way of the playbook advised. */
    public PlanPreferences(Set<Habit> habits, Focus focus) {
        this(habits, focus, dev.kindred.core.guide.Playbook.Mode.NORMAL, null);
    }

    public PlanPreferences {
        Objects.requireNonNull(habits);
        Objects.requireNonNull(focus);
        Objects.requireNonNull(mode);
        var kept = EnumSet.noneOf(Habit.class);
        kept.addAll(habits);
        kept.remove(Habit.NONE);
        habits = Set.copyOf(kept);
    }

    public boolean has(Habit habit) { return habits.contains(habit); }

    /** The way of the playbook the strategist advised, if any (F-25c8). */
    public java.util.Optional<dev.kindred.core.guide.Playbook.Approach> recommended() { return java.util.Optional.ofNullable(advised); }
}
