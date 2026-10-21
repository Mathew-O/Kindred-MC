package dev.kindred.core.survival;

import dev.kindred.core.guide.Need;
import dev.kindred.core.guide.NeedsAssessment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Local, deterministic plan from a lit Nether portal to the End portal, the way players do it: stock up, go through,
 * find a fortress and take blaze rods from its blazes, take ender pearls from endermen, come back, make eyes of ender,
 * throw one, walk a way across its flight and throw another, follow the crossing to the stronghold, dig down into it
 * and find the portal room. Like {@link PortalRunPlanner} it reads only the player's own items and sightings and
 * names the next steps; the adapter turns each into one bounded, admitted skill and skips what it cannot act on yet.
 *
 * <p>Twelve eyes can fill any portal frame; six are enough to find the stronghold and its portal room with a throw or
 * two to spare, so a pearl hunt that has stalled with six in hand moves on and takes more pearls from endermen met on
 * the way. Nothing here enters the End.
 *
 * <p>What the End fight will need (a bow and arrows for the crystals, blocks, a water bucket, food) is known from the
 * start: the follow-up queue's chunks ({@link Provisions}) are done as the chance comes along the way (a chicken in
 * sight while feathers are wanted, string and sticks in hand for the bow), and whatever is still missing is gathered
 * at the stronghold before going down to it, unless that has stalled.
 */
public final class StrongholdRunPlanner {
    /** Rods for twelve eyes: each rod makes two blaze powder. */
    public static final int RODS_TARGET = 6;
    /** Pearls for twelve eyes, enough to fill any End portal frame. */
    public static final int PEARLS_TARGET = 12;
    /** Eyes enough to find the stronghold and its portal room: two throws to cross, the rest for breaks and the room. */
    public static final int EYES_MIN = 6;
    /** Food carried through the portal: the Nether has little to eat. */
    public static final int PREP_FOOD = 12;
    /** Blocks carried for bridging lava, pillaring and walling off. */
    public static final int PREP_BLOCKS = 48;
    /** Planks in a boat. */
    public static final int BOAT_PLANKS = 5;

    public enum Stage { PREPARE, NETHER, FORTRESS, BLAZE_RODS, PEARLS, RETURN, EYES, LOCATE, TRAVEL, PROVISION, DESCEND, PORTAL_ROOM, DONE }

    public enum Action {
        DONE,
        /** Hunt an animal in sight, and look for animals when none is in sight. */
        HUNT, SEEK_FOOD,
        /** Dig plain stone for blocks. */
        GATHER_STONE,
        /** Craft {@link Step#item()} from what is carried (at a table where the recipe needs one). */
        CRAFT,
        /** Walk into the known portal and wait in it to cross. */
        ENTER_PORTAL,
        /** Explore the Nether for a fortress, or go into the one seen. */
        FIND_FORTRESS,
        /** Fight a blaze in sight; look for blazes about the fortress. */
        FIGHT_BLAZE, SEEK_BLAZE,
        /** Fight an enderman in sight; look for endermen. */
        FIGHT_ENDERMAN, SEEK_ENDERMAN,
        /** Throw an eye of ender and watch where it flies; the second throw from well to the side of the first. */
        THROW_EYE,
        /** Head for where the flights cross. */
        TRAVEL_TO_STRONGHOLD,
        /** Dig down there until the stronghold's stone bricks show. */
        DIG_TO_STRONGHOLD,
        /** Explore the stronghold for the portal room, throwing an eye when the way is unclear. */
        FIND_PORTAL_ROOM,
        /** One chunk of the follow-up queue: gather or craft {@link Step#count()} of the need named by {@link Step#item()}. */
        FOLLOW_UP
    }

    /**
     * What the objectives ahead still need, from the follow-up queue: the chunks of work left, those the player can get
     * at now ({@code atHand}: a chicken in sight while feathers are wanted, planks for sticks), what it holds of each
     * need, and whether all of it is due now (before the stronghold, until the descent begins or the gathering has
     * stalled).
     */
    public record Provisions(List<NeedsAssessment.Chunk> chunks, Set<Need> atHand, Map<Need, Integer> held, boolean due) {
        public static final Provisions NONE = new Provisions(List.of(), Set.of(), Map.of(), false);

        public Provisions {
            chunks = List.copyOf(chunks);
            atHand = Set.copyOf(atHand);
            held = Map.copyOf(held);
        }

        /** A chunk the player can do now, on the way: one it can get at, or a craft whose inputs it holds. */
        public boolean now(NeedsAssessment.Chunk chunk) {
            return atHand.contains(chunk.need()) || NeedsAssessment.ready(chunk, held);
        }
    }

    /**
     * @param item  recipe result for CRAFT, otherwise empty
     * @param count how many of the step's material are still wanted
     */
    public record Step(Action action, String item, int count, Stage stage, String why) {
        public Step {
            Objects.requireNonNull(action);
            Objects.requireNonNull(item);
            Objects.requireNonNull(stage);
            Objects.requireNonNull(why);
        }

        static Step of(Action action, Stage stage, String why) { return new Step(action, "", 0, stage, why); }
        static Step craft(String item, int count, Stage stage, String why) { return new Step(Action.CRAFT, item, count, stage, why); }

        static Step followUp(NeedsAssessment.Chunk chunk, Stage stage) {
            String verb = chunk.kind() == NeedsAssessment.Kind.GATHER ? "Gather " : "Craft ";
            return new Step(Action.FOLLOW_UP, chunk.need().name(), chunk.count(), stage,
                    verb + chunk.count() + " " + chunk.need().words(chunk.count()) + " for " + chunk.objective().words());
        }
    }

    private StrongholdRunPlanner() { }

    /** {@link #stage(StrongholdRunSnapshot)}, with the End fight's needs gathered before going down when they are due. */
    public static Stage stage(StrongholdRunSnapshot s, Provisions provisions) {
        Stage stage = stage(s);
        return stage == Stage.DESCEND && provisions.due() && !provisions.chunks().isEmpty() ? Stage.PROVISION : stage;
    }

    public static Stage stage(StrongholdRunSnapshot s) {
        Objects.requireNonNull(s);
        if (s.portalRoomSeen()) return Stage.DONE;
        if (s.dimension() == StrongholdRunSnapshot.Dimension.NETHER) {
            if (rodsShort(s)) return s.fortressSeen() ? Stage.BLAZE_RODS : Stage.FORTRESS;
            if (!netherDone(s)) return Stage.PEARLS;
            return Stage.RETURN;
        }
        // Before the search, enough for the whole of it; once begun, only for the throws still to make, so an eye
        // that breaks in flight never sends it back to the Nether while it can still find the place.
        boolean searching = s.throwsRecorded() > 0 || s.strongholdEstimate() || s.strongholdSeen();
        int needed = !searching ? EYES_MIN : s.strongholdEstimate() || s.strongholdSeen() ? 0 : Math.max(1, 2 - s.throwsRecorded());
        if (s.eyesPossible() < needed) return prepared(s) ? Stage.NETHER : Stage.PREPARE;
        if (s.eyes() < s.eyesPossible()) return Stage.EYES;
        if (!s.strongholdEstimate()) return Stage.LOCATE;
        if (!s.nearEstimate() && !s.strongholdSeen()) return Stage.TRAVEL;
        if (!s.strongholdSeen()) return Stage.DESCEND;
        return Stage.PORTAL_ROOM;
    }

    /** {@link #plan(StrongholdRunSnapshot, Provisions)} with nothing queued. */
    public static List<Step> plan(StrongholdRunSnapshot s) {
        return plan(s, Provisions.NONE);
    }

    /**
     * Steps in priority order: food when out of it, an enderman in sight while pearls are short, a blaze in sight in
     * the Nether while rods are short, follow-ups the player can do now on the way, then the stage's own steps.
     */
    public static List<Step> plan(StrongholdRunSnapshot s, Provisions provisions) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(provisions);
        var steps = new ArrayList<Step>();
        Stage stage = stage(s, provisions);
        if (stage == Stage.DONE) {
            steps.add(Step.of(Action.DONE, Stage.DONE, "Found the End portal"));
            return steps;
        }
        boolean nether = s.dimension() == StrongholdRunSnapshot.Dimension.NETHER;
        if (s.edibleFood() == 0 && s.foodLevel() <= 14 && !nether) {
            steps.add(new Step(Action.HUNT, "", 1, stage, "Out of food; hunt an animal in sight"));
        }
        // Pearls are the scarce part: an enderman met anywhere is worth the fight while they are short.
        if (s.endermanSeen() && s.enderPearls() + s.eyes() < PEARLS_TARGET && s.swordTier() >= 2) {
            steps.add(Step.of(Action.FIGHT_ENDERMAN, stage, "An enderman in sight: its pearl"));
        }
        if (nether && s.blazeSeen() && rodsShort(s) && s.swordTier() >= 2) {
            steps.add(Step.of(Action.FIGHT_BLAZE, stage, "A blaze in sight: its rod"));
        }
        // The queue's chunks the chance has come for: a detour of a few seconds, not a stop to the route.
        for (var chunk : provisions.chunks()) if (provisions.now(chunk)) steps.add(Step.followUp(chunk, stage));
        switch (stage) {
            case PREPARE -> prepare(s, steps);
            case NETHER -> steps.add(Step.of(Action.ENTER_PORTAL, stage, "Through the portal to the Nether"));
            case FORTRESS -> steps.add(Step.of(Action.FIND_FORTRESS, stage, "Find a Nether fortress for blazes"));
            case BLAZE_RODS -> steps.add(new Step(Action.SEEK_BLAZE, "", RODS_TARGET - s.blazeRods(), stage, "Look for blazes about the fortress"));
            case PEARLS -> steps.add(new Step(Action.SEEK_ENDERMAN, "", PEARLS_TARGET - s.enderPearls() - s.eyes(), stage, "Look for endermen for pearls"));
            case RETURN -> steps.add(Step.of(Action.ENTER_PORTAL, stage, "Back through the portal with rods and pearls"));
            case EYES -> {
                if (s.blazePowder() == 0 && s.blazeRods() > 0) {
                    steps.add(Step.craft("blaze_powder", 1, stage, "Grind a blaze rod into powder"));
                }
                steps.add(Step.craft("ender_eye", s.eyesPossible() - s.eyes(), stage, "Make eyes of ender"));
            }
            case LOCATE -> steps.add(Step.of(Action.THROW_EYE, stage, s.throwsRecorded() == 0
                    ? "Throw an eye of ender and watch where it goes" : "Throw another from well to the side, to cross the first"));
            case TRAVEL -> steps.add(Step.of(Action.TRAVEL_TO_STRONGHOLD, stage, "Head for where the eyes' flights cross"));
            case PROVISION -> {
                for (var chunk : provisions.chunks()) if (!provisions.now(chunk)) steps.add(Step.followUp(chunk, stage));
            }
            case DESCEND -> steps.add(Step.of(Action.DIG_TO_STRONGHOLD, stage, "Dig down to the stronghold"));
            case PORTAL_ROOM -> steps.add(Step.of(Action.FIND_PORTAL_ROOM, stage, "Find the portal room"));
            default -> { }
        }
        return steps;
    }

    /** Short of powder for the eyes wanted, counting rods (two powder each), powder and eyes already made. */
    static boolean rodsShort(StrongholdRunSnapshot s) {
        return 2 * s.blazeRods() + s.blazePowder() + s.eyes() < PEARLS_TARGET;
    }

    /** The Nether's part is done: rods and pearls for the eyes wanted, or enough for the search with the hunt stalled. */
    static boolean netherDone(StrongholdRunSnapshot s) {
        if (rodsShort(s)) return false;
        return s.enderPearls() + s.eyes() >= PEARLS_TARGET || s.pearlHuntStalled() && s.eyesPossible() >= EYES_MIN;
    }

    /** Ready to go through: food, blocks, a spare pickaxe, a stone sword or better, and the way there. */
    static boolean prepared(StrongholdRunSnapshot s) {
        return s.portalKnown() && s.edibleFood() >= PREP_FOOD && s.blocks() >= PREP_BLOCKS && s.pickaxes() >= 2 && s.swordTier() >= 2;
    }

    private static void prepare(StrongholdRunSnapshot s, List<Step> steps) {
        Stage stage = Stage.PREPARE;
        // Iron in hand goes into what keeps it alive over there first: a sword, a shield, then a helmet and boots.
        int iron = s.ironIngots();
        if (s.swordTier() < 3 && iron >= 2) {
            steps.add(Step.craft("iron_sword", 1, stage, "An iron sword for blazes and endermen"));
            iron -= 2;
        }
        if (s.shields() == 0 && iron >= 1) {
            steps.add(Step.craft("shield", 1, stage, "A shield against fireballs"));
            iron -= 1;
        }
        if (s.armorPieces() < 2 && iron >= 5) {
            steps.add(Step.craft("iron_helmet", 1, stage, "An iron helmet"));
            iron -= 5;
        }
        if (s.armorPieces() < 3 && iron >= 4) steps.add(Step.craft("iron_boots", 1, stage, "Iron boots"));
        if (s.edibleFood() < PREP_FOOD) {
            steps.add(new Step(Action.HUNT, "", PREP_FOOD - s.edibleFood(), stage, "Stock food for the Nether"));
            steps.add(new Step(Action.SEEK_FOOD, "", PREP_FOOD - s.edibleFood(), stage, "Look for animals to stock food"));
        }
        if (s.pickaxes() < 2) steps.add(Step.craft("stone_pickaxe", 1, stage, "A spare pickaxe"));
        if (s.swordTier() < 2) steps.add(Step.craft("stone_sword", 1, stage, "A sword"));
        if (s.blocks() < PREP_BLOCKS) steps.add(new Step(Action.GATHER_STONE, "", PREP_BLOCKS - s.blocks(), stage, "Blocks for bridging and walling off"));
        // A boat, from wood it carries, for the water on the way to the stronghold: swimming an ocean is slow, and its
        // drowned are deadly (the staged End runs drowned twice mid-ocean). Not a reason to stay: going through does
        // not wait for it.
        if (s.boats() == 0) {
            if (s.planks() >= BOAT_PLANKS) steps.add(Step.craft("boat", 1, stage, "A boat for crossing water"));
            else if (s.planks() + 4 * s.logs() >= BOAT_PLANKS) steps.add(Step.craft("planks", 1, stage, "Planks for a boat"));
        }
    }
}
