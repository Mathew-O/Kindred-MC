package dev.kindred.core.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Local, deterministic plan from the End portal to beating the game, the way players do it: fill the frame with eyes
 * and jump in; get onto the main island; shoot the End crystals off their pillars from the ground, and pillar up to the
 * two in iron cages to open them first; then the dragon, struck on the head while it perches on the exit portal's pillar
 * and kept clear of while it flies, out of its breath and away from the void; and when it is dead, home through the exit
 * portal. Like the other planners it reads only the player's own items and sightings and names the next steps; the
 * adapter turns each into one bounded, admitted skill and skips what it cannot act on yet.
 */
public final class EndFightPlanner {
    /** Health below which the player keeps out of the dragon's way until it has healed. */
    public static final float LOW_HEALTH = 8;

    public enum Stage { FILL_PORTAL, SHORT_OF_EYES, ENTER_END, REACH_ISLAND, CRYSTALS, DRAGON, EXIT, DONE }

    public enum Action {
        DONE,
        /** Put an eye of ender into a frame block of the ring that lacks one; look about the room for the rest. */
        FILL_FRAME,
        /** Into the open End portal (or, in the End, the exit portal). */
        ENTER_PORTAL,
        /** Walk or make a way from the arrival platform onto the main island. */
        TO_ISLAND,
        /** Shoot an End crystal it can hit from where it stands, or from a place it can see it from. */
        SHOOT_CRYSTAL,
        /** Pillar up beside a caged crystal's pillar, break the bars, and come down again. */
        OPEN_CAGE,
        /** Walk round the island to see the crystals it has not seen. */
        SEEK_CRYSTALS,
        /** Out of the dragon's breath. */
        LEAVE_BREATH,
        /** Strike the perched dragon's head. */
        STRIKE_DRAGON,
        /** Shoot the dragon in flight, when it is in range. */
        SHOOT_DRAGON,
        /** Keep near the exit portal's pillar, away from the void, while the dragon flies. */
        HOLD_GROUND
    }

    public record Step(Action action, Stage stage, String why) {
        public Step {
            Objects.requireNonNull(action);
            Objects.requireNonNull(stage);
            Objects.requireNonNull(why);
        }
    }

    private EndFightPlanner() { }

    public static Stage stage(EndFightSnapshot s) {
        Objects.requireNonNull(s);
        if (!s.inEnd()) {
            if (s.dragonKilled()) return Stage.DONE;
            if (s.portalOpen()) return Stage.ENTER_END;
            return s.eyes() >= 1 ? Stage.FILL_PORTAL : Stage.SHORT_OF_EYES;
        }
        if (s.dragonKilled() || s.exitOpen() && !s.dragonAlive()) return Stage.EXIT;
        if (!s.onIsland()) return Stage.REACH_ISLAND;
        // The crystals heal the dragon: they come first while there is a way to break them.
        if (s.crystals() > 0 && (s.bow() && s.arrows() > 0 || s.caged() > 0 && s.blocks() >= 16)) return Stage.CRYSTALS;
        return Stage.DRAGON;
    }

    /** Steps in priority order: out of the dragon's breath first, then the stage's own. */
    public static List<Step> plan(EndFightSnapshot s) {
        Objects.requireNonNull(s);
        var steps = new ArrayList<Step>();
        Stage stage = stage(s);
        if (s.inEnd() && s.inBreath()) steps.add(new Step(Action.LEAVE_BREATH, stage, "Out of the dragon's breath"));
        switch (stage) {
            case DONE -> steps.add(new Step(Action.DONE, stage, "The dragon is dead"));
            case FILL_PORTAL -> steps.add(new Step(Action.FILL_FRAME, stage, s.framesSeen() < EndFightSnapshot.RING
                    ? "Fill the End portal's frame, and look for the rest of the ring" : "Fill the End portal's frame with eyes"));
            case SHORT_OF_EYES -> { } // The stronghold leg fetches more eyes.
            case ENTER_END -> steps.add(new Step(Action.ENTER_PORTAL, stage, "Into the End portal"));
            case REACH_ISLAND -> steps.add(new Step(Action.TO_ISLAND, stage, "Onto the main island"));
            case CRYSTALS -> {
                if (s.shootable() > 0 && s.bow() && s.arrows() > 0) steps.add(new Step(Action.SHOOT_CRYSTAL, stage, "Shoot an End crystal off its pillar"));
                if (s.caged() > 0 && s.blocks() >= 16) steps.add(new Step(Action.OPEN_CAGE, stage, "Open a caged crystal's bars"));
                steps.add(new Step(Action.SEEK_CRYSTALS, stage, "Look for the crystals left"));
            }
            case DRAGON -> {
                // Hurt, it keeps clear until it heals; then the head when it perches, arrows while it flies in range.
                if (s.health() >= LOW_HEALTH && s.dragonPerched() && s.swordTier() >= 1) {
                    steps.add(new Step(Action.STRIKE_DRAGON, stage, "Strike the perched dragon's head"));
                }
                if (s.health() >= LOW_HEALTH && s.bow() && s.arrows() > 0) steps.add(new Step(Action.SHOOT_DRAGON, stage, "Shoot the dragon"));
                steps.add(new Step(Action.HOLD_GROUND, stage, "Keep by the exit portal, away from the edge"));
            }
            case EXIT -> steps.add(new Step(Action.ENTER_PORTAL, stage, "Home through the exit portal"));
            default -> { }
        }
        return steps;
    }
}
