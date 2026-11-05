package dev.kindred.core.match;

import dev.kindred.core.construction.Houses;

import java.util.Collection;
import java.util.Optional;

/**
 * In plain terms: in a Co-op Survival world the AI players keep to the humans' pace. Each step of the game is marked
 * by one of vanilla's own advancements, the ones every player sees announced: stone mined, iron smelted, a diamond
 * found, an item enchanted, the Nether entered, the End entered. An AI player does not move its base on to a stage
 * that belongs to a step no human in the world has reached yet. A house waits until someone has smelted iron, a manor
 * round an enchanting room until someone has enchanted. The group's furthest step is kept, so it does not go back
 * when that human logs off.
 *
 * Technical: The group's pace (M-01e, M-01's "progress tracks the human: the AI player does not run ahead of the
 * group's milestones", owner 2026-10-23). A base stage waits on its step ({@link #hold}); so does a trip into another
 * world ({@link #holdTrip}): into the Nether once a human has entered it, into the End (and so to the dragon, which
 * in Co-op is fought alongside the humans, never ahead of them) once a human has. The way home is never held. The milestones are vanilla's story advancements, read from the humans'
 * own advancement progress: announced to everyone, so nothing hidden is known. A stage is held only while the group
 * has a step and it is short of the stage's. A world in which no human has reached any step yet is not paced: an AI
 * player's decisions are never gated on a human being present (AGENTS.md), only kept from outrunning the humans
 * once there are some. Pure.
 */
public final class GroupPace {
    /** The steps of the game, in order, each with the vanilla advancement that marks it. */
    public enum Step {
        STONE("minecraft:story/mine_stone", "mined stone"),
        IRON("minecraft:story/smelt_iron", "smelted iron"),
        DIAMOND("minecraft:story/mine_diamond", "found a diamond"),
        ENCHANTING("minecraft:story/enchant_item", "enchanted an item"),
        NETHER("minecraft:story/enter_the_nether", "entered the Nether"),
        END("minecraft:story/enter_the_end", "entered the End");

        /** The advancement that marks it. */
        public final String advancement;
        /** What reaching it is, said of a player. */
        public final String deed;

        Step(String advancement, String deed) {
            this.advancement = advancement;
            this.deed = deed;
        }

        /** The step an advancement marks, if any. */
        public static Optional<Step> of(String advancement) {
            for (Step step : values()) if (step.advancement.equals(advancement)) return Optional.of(step);
            return Optional.empty();
        }

        /** The step by its name, as saved. */
        public static Optional<Step> named(String name) {
            for (Step step : values()) if (step.name().equalsIgnoreCase(name.trim())) return Optional.of(step);
            return Optional.empty();
        }
    }

    private GroupPace() { }

    /** The furthest step among the advancements done, if any. */
    public static Optional<Step> furthest(Collection<String> advancementsDone) {
        Step best = null;
        for (String id : advancementsDone) {
            var step = Step.of(id);
            if (step.isPresent() && (best == null || step.get().compareTo(best) > 0)) best = step.get();
        }
        return Optional.ofNullable(best);
    }

    /** The further of two, either possibly none. */
    public static Optional<Step> further(Optional<Step> a, Optional<Step> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a.get().compareTo(b.get()) >= 0 ? a : b;
    }

    /** The step a base stage belongs to: none for the cabin, the first shelter; iron for a house; enchanting for a manor. */
    public static Optional<Step> stepOf(Houses.Stage stage) {
        return switch (stage) {
            case CABIN -> Optional.empty();
            case HOUSE -> Optional.of(Step.IRON);
            case MANOR -> Optional.of(Step.ENCHANTING);
        };
    }

    /**
     * Why the stage waits for the group, or none when it may go ahead: the world is paced (Co-op Survival) and the
     * group has reached a step, short of the stage's.
     */
    public static Optional<String> hold(Houses.Stage stage, boolean paced, Optional<Step> group) {
        if (!paced || group.isEmpty()) return Optional.empty();
        var needed = stepOf(stage);
        if (needed.isEmpty() || group.get().compareTo(needed.get()) >= 0) return Optional.empty();
        return Optional.of("Keeping to the group's pace: a " + stage.noun + " once someone has " + needed.get().deed
                + " (the furthest so far: " + group.get().deed + ")");
    }

    /**
     * Why a trip into {@code into} (the Nether or the End) waits for the group, or none when it may go: the world is
     * paced and the group has a step, short of entering that world. Any other step is never a trip's to wait for.
     */
    public static Optional<String> holdTrip(Step into, boolean paced, Optional<Step> group) {
        if (!paced || group.isEmpty() || (into != Step.NETHER && into != Step.END)) return Optional.empty();
        if (group.get().compareTo(into) >= 0) return Optional.empty();
        return Optional.of("Keeping to the group's pace: into " + (into == Step.NETHER ? "the Nether" : "the End") + " once someone has "
                + into.deed + " (the furthest so far: " + group.get().deed + ")");
    }
}
