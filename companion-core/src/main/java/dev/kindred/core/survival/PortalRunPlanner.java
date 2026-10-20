package dev.kindred.core.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Local, deterministic plan for earning a Nether portal from an empty inventory. It reads only the player's
 * own items and observations and names the next material step; the adapter turns a step into one bounded,
 * admitted skill and may skip steps it cannot act on yet (for example hunting with no animal in sight).
 *
 * <p>Route: logs, wooden pickaxe, cobblestone, stone pickaxe and sword, furnace, fifteen iron (iron pickaxe,
 * bucket, chestplate, flint and steel), a water bucket, three diamonds and a diamond pickaxe, ten obsidian made by pouring
 * water onto lava sources and mining them, flint from gravel, then a ten-obsidian frame with cobblestone
 * corners, lit with the owned flint and steel.
 *
 * <p>That route is the cheapest in an ordinary world, not the only one. Where {@link WorldFacts} offers more than
 * one method for a sub-goal, the planner picks the cheapest from the player's items and what it has seen; the
 * adapter passes fresh facts on every pass, so the plan changes as soon as the world does. The first alternative
 * is the frame itself: obsidian mined with a diamond pickaxe, or cast in place from lava and water with a second
 * bucket and no diamonds at all.
 */
public final class PortalRunPlanner {
    public static final int PORTAL_OBSIDIAN = 10;
    public static final int PORTAL_CORNERS = 4;
    /** Cobblestone kept for tools, a furnace, portal corners and a few placement blocks. */
    public static final int COBBLE_TARGET = 24;
    /** Planks in a shield, with one iron ingot. */
    public static final int SHIELD_PLANKS = 6;
    /** Trees this close are worth a detour for a shield's wood or spare sticks; farther ones are not. */
    static final int TREE_RANGE = 24;
    /**
     * Sticks kept beyond the pickaxes still to make: two replacements for ones that wear out. Staircases and branch
     * mines wear out a stone pickaxe every few minutes; live run 49 used three before its sixth iron and was left at
     * Y=19 with cobblestone, a table and no sticks.
     */
    static final int SPARE_STICKS = 4;
    public static final int DIAMONDS_NEEDED = 3;
    /** Food items to carry before work takes the player away from animals. */
    public static final int FOOD_STOCK = 3;

    public enum Stage { WOOD, STONE, IRON, DIAMOND, OBSIDIAN, FLINT, PORTAL, DONE }

    public enum Action {
        DONE, COLLECT_SMELTED, HUNT, GATHER_LOGS, CRAFT, PLACE_TABLE, PLACE_FURNACE, GATHER_STONE,
        MINE_COAL, MINE_IRON, SMELT, AWAIT_SMELT, FILL_WATER, MINE_DIAMOND, MAKE_OBSIDIAN, GATHER_FLINT,
        BUILD_PORTAL, IGNITE_PORTAL,
        /** Cast the frame in place: lava poured into each frame cell and hardened with water. */
        CAST_PORTAL
    }

    /**
     * @param item  recipe result for CRAFT ("planks" means any planks the owned logs make), item to smelt for
     *              SMELT, otherwise empty
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
        static Step craft(String item, Stage stage, String why) { return new Step(Action.CRAFT, item, 1, stage, why); }
    }

    private PortalRunPlanner() { }

    /** {@link #plan(PortalRunSnapshot, WorldFacts)} in an ordinary world with nothing observed yet. */
    public static List<Step> plan(PortalRunSnapshot s) {
        return plan(s, WorldFacts.ordinary());
    }

    /** Steps in priority order: opportunistic upkeep first, then the single next material step of the route. */
    public static List<Step> plan(PortalRunSnapshot s, WorldFacts facts) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(facts);
        var steps = new ArrayList<Step>();
        if (s.portalLit()) {
            steps.add(Step.of(Action.DONE, Stage.DONE, "The Nether portal is built and lit"));
            return steps;
        }
        if (s.furnaceNearby() && s.furnaceOutput() > 0) {
            steps.add(Step.of(Action.COLLECT_SMELTED, Stage.IRON, "Collect smelted items from the furnace"));
        }
        if (s.edibleFood() == 0 && s.foodLevel() <= 14) {
            steps.add(new Step(Action.HUNT, "", 1, stage(s, facts), "Out of food; hunt an animal in sight"));
        } else if (s.edibleFood() < FOOD_STOCK) {
            // There are no animals deep underground: take a little food while some are in sight.
            steps.add(new Step(Action.HUNT, "", FOOD_STOCK - s.edibleFood(), stage(s, facts), "Stock some food while animals are around"));
        }
        steps.add(primary(s, facts));
        return steps;
    }

    public static Stage stage(PortalRunSnapshot s) {
        return stage(s, WorldFacts.ordinary());
    }

    public static Stage stage(PortalRunSnapshot s, WorldFacts facts) {
        if (s.portalLit()) return Stage.DONE;
        if (s.portalFramed() || s.obsidian() >= PORTAL_OBSIDIAN && s.flintAndSteel() > 0) return Stage.PORTAL;
        if (s.pickaxeTier() <= 0) return Stage.WOOD;
        if (s.pickaxeTier() == 1) return Stage.STONE;
        WorldFacts.Method frame = frameMethod(s, facts);
        if (s.pickaxeTier() == 2 || ironShortfall(s, frame) > 0 && s.pickaxeTier() < 4) return Stage.IRON;
        if (frame == WorldFacts.Method.MINE_OBSIDIAN && s.pickaxeTier() == 3) return Stage.DIAMOND;
        if (s.obsidian() < PORTAL_OBSIDIAN) return Stage.OBSIDIAN;
        return Stage.FLINT;
    }

    /**
     * How the frame's obsidian will be got: the cheaper available method, in rough units of work. Mining needs a
     * diamond pickaxe (free once owned, cheap with diamonds in hand or in sight, a long search otherwise); casting
     * needs a second bucket, three more iron. Each obsidian costs about the same either way, a trip to the lava.
     */
    public static WorldFacts.Method frameMethod(PortalRunSnapshot s, WorldFacts facts) {
        boolean mine = facts.can(WorldFacts.Method.MINE_OBSIDIAN), cast = facts.can(WorldFacts.Method.CAST_OBSIDIAN);
        if (!cast) return WorldFacts.Method.MINE_OBSIDIAN;
        if (!mine) return WorldFacts.Method.CAST_OBSIDIAN;
        return castCost(s) < mineCost(s, facts) ? WorldFacts.Method.CAST_OBSIDIAN : WorldFacts.Method.MINE_OBSIDIAN;
    }

    private static double mineCost(PortalRunSnapshot s, WorldFacts facts) {
        if (s.pickaxeTier() >= 4) return 0;
        int missing = Math.max(0, DIAMONDS_NEEDED - s.diamonds());
        return 2 + missing * (facts.knows(WorldFacts.Source.DIAMOND_ORE) ? 2.0 : 8.0);
    }

    private static double castCost(PortalRunSnapshot s) {
        int buckets = s.emptyBuckets() + s.waterBuckets();
        return 2 + Math.max(0, 2 - buckets) * 3.0;
    }

    private static Step primary(PortalRunSnapshot s, WorldFacts facts) {
        WorldFacts.Method frame = frameMethod(s, facts);
        // Wood: a crafting table and a wooden pickaxe, with logs to spare for sticks and fuel.
        if (s.pickaxeTier() <= 0) {
            // A worn-out or lost pickaxe with cobblestone already in hand goes straight back to stone.
            if (s.cobble() >= 3) return craftAtTable(s, "stone_pickaxe", 0, 2, Stage.WOOD);
            boolean haveTable = s.tableNearby() || s.craftingTables() > 0;
            int woodWanted = haveTable ? 6 : 12;
            if (s.woodUnits() < woodWanted) {
                return new Step(Action.GATHER_LOGS, "", Math.max(1, (woodWanted - s.woodUnits() + 3) / 4), Stage.WOOD,
                        haveTable ? "Gather logs for a wooden pickaxe" : "Gather logs for a table and a wooden pickaxe");
            }
            return craftAtTable(s, "wooden_pickaxe", 3, 2, Stage.WOOD);
        }
        // Stone: cobblestone for a stone pickaxe, sword, furnace and portal corners.
        if (s.pickaxeTier() == 1) {
            if (s.cobble() < 3) {
                return new Step(Action.GATHER_STONE, "", 3 - s.cobble(), Stage.STONE, "Mine cobblestone for a stone pickaxe");
            }
            return craftAtTable(s, "stone_pickaxe", 0, 2, Stage.STONE);
        }
        if (s.swordTier() < 2 && s.cobble() >= 2) {
            return craftAtTable(s, "stone_sword", 0, 1, Stage.STONE);
        }
        // Sticks for the pickaxes still to make and spares for ones that wear out, made while trees are in sight
        // or from wood already carried: there are none deep below, and a climb back up costs more than the sticks.
        boolean treesNear = facts.distance(WorldFacts.Source.TREE).orElse(Integer.MAX_VALUE) <= TREE_RANGE;
        int spareWood = s.woodUnits() - (s.shields() == 0 ? SHIELD_PLANKS : 0);
        if (s.sticks() < sticksReserved(s, frame) && (treesNear || spareWood >= 2)) {
            if (s.planks() >= 2 && spareWood >= 2) return Step.craft("stick", Stage.STONE, "Craft spare sticks for later pickaxes");
            if (s.logs() > 0 && spareWood >= 2) return Step.craft("planks", Stage.STONE, "Craft planks for spare sticks");
            return new Step(Action.GATHER_LOGS, "", 1, Stage.STONE, "Gather a log for spare sticks");
        }
        // Planks for a shield, taken while trees are in sight: arrows kill more players in caves than anything else.
        if (s.shields() == 0 && s.pickaxeTier() < 3 && s.woodUnits() < SHIELD_PLANKS
                && treesNear) {
            return new Step(Action.GATHER_LOGS, "", 2, Stage.STONE, "Gather logs for a shield");
        }
        // Kept until the pickaxe is final: diamond on the classic route, iron when the frame is cast.
        if (s.cobble() < COBBLE_TARGET - 6 && s.pickaxeTier() < (frame == WorldFacts.Method.CAST_OBSIDIAN ? 3 : 4)) {
            return new Step(Action.GATHER_STONE, "", COBBLE_TARGET - s.cobble(), Stage.STONE, "Mine cobblestone for a furnace and portal corners");
        }

        // Iron: pickaxe, bucket and flint and steel, smelted in an owned furnace.
        int ironWanted = ironShortfall(s, frame);
        int ironHave = s.ironIngots() + s.rawIron();
        // With a shield's wood in hand, the first iron is smelted and made into one at once, as players do: the caves
        // where the rest of the iron is found are where skeletons end most runs.
        boolean shieldFirst = s.shields() == 0 && s.woodUnits() >= SHIELD_PLANKS;
        // Then the iron pickaxe as soon as there is iron for it: it lasts twice as long as stone, and staircases and
        // branch mines wear a stone one out every few minutes (live run 50 used six in one life before its iron).
        boolean pickaxeFirst = !shieldFirst && s.pickaxeTier() < 3 && ironHave >= 3;
        if (shieldFirst && s.ironIngots() >= 1) return craftAtTable(s, "shield", SHIELD_PLANKS, 0, Stage.IRON);
        if (ironHave < ironWanted && !(shieldFirst && s.rawIron() > 0) && !pickaxeFirst) {
            return new Step(Action.MINE_IRON, "", ironWanted - ironHave, Stage.IRON, "Mine iron ore (" + ironHave + "/" + ironWanted + ")");
        }
        if (s.rawIron() > 0) {
            if (s.furnaceSmelting()) return Step.of(Action.AWAIT_SMELT, Stage.IRON, "Wait for the furnace to smelt iron");
            // Wood kept for a shield is not fuel.
            if (s.coal() == 0 && spareWood < (s.rawIron() * 2 + 2) / 3) {
                return new Step(Action.MINE_COAL, "", 1, Stage.IRON, "Find fuel to smelt iron");
            }
            if (!s.furnaceNearby() && s.furnaces() == 0) {
                if (s.cobble() < 8) return new Step(Action.GATHER_STONE, "", 8 - s.cobble(), Stage.IRON, "Mine cobblestone for a furnace");
                return craftAtTable(s, "furnace", 0, 0, Stage.IRON);
            }
            return new Step(Action.SMELT, "raw_iron", s.rawIron(), Stage.IRON, "Smelt raw iron");
        }
        if (s.pickaxeTier() < 3) return craftAtTable(s, "iron_pickaxe", 0, 2, Stage.IRON);
        if (s.emptyBuckets() + s.waterBuckets() < bucketsWanted(frame)) return craftAtTable(s, "bucket", 0, 0, Stage.IRON);
        // Caves below are full of skeletons and zombies: an iron chestplate before going deep.
        if (s.chestArmorTier() < 3 && s.pickaxeTier() < 4) return craftAtTable(s, "iron_chestplate", 0, 0, Stage.IRON);

        // Diamonds for a pickaxe that can mine obsidian, unless the frame is cast instead.
        if (frame == WorldFacts.Method.MINE_OBSIDIAN && s.pickaxeTier() < 4) {
            if (s.diamonds() < DIAMONDS_NEEDED) {
                return new Step(Action.MINE_DIAMOND, "", DIAMONDS_NEEDED - s.diamonds(), Stage.DIAMOND, "Strip-mine for diamonds (" + s.diamonds() + "/3)");
            }
            return craftAtTable(s, "diamond_pickaxe", 0, 2, Stage.DIAMOND);
        }

        // Cast: the frame hardens in place from lava poured into it and water poured over it.
        if (frame == WorldFacts.Method.CAST_OBSIDIAN && !s.portalFramed() && s.obsidian() < PORTAL_OBSIDIAN) {
            if (s.waterBuckets() == 0) return Step.of(Action.FILL_WATER, Stage.OBSIDIAN, "Fill a bucket with water");
            int blocks = castBlocks(s);
            if (s.cobble() < blocks) return new Step(Action.GATHER_STONE, "", blocks - s.cobble(), Stage.OBSIDIAN, "Mine blocks for the casting mould");
            return new Step(Action.CAST_PORTAL, "", PORTAL_OBSIDIAN - s.obsidian(), Stage.OBSIDIAN, "Cast the portal frame from lava and water");
        }

        // Obsidian: water poured onto still lava, then mined with the diamond pickaxe.
        if (!s.portalFramed() && s.obsidian() < PORTAL_OBSIDIAN) {
            if (s.waterBuckets() == 0) return Step.of(Action.FILL_WATER, Stage.OBSIDIAN, "Fill a bucket with water");
            return new Step(Action.MAKE_OBSIDIAN, "", PORTAL_OBSIDIAN - s.obsidian(), Stage.OBSIDIAN, "Make and mine obsidian (" + s.obsidian() + "/10)");
        }

        // Flint and steel.
        if (s.flintAndSteel() == 0) {
            if (s.flint() == 0) return Step.of(Action.GATHER_FLINT, Stage.FLINT, "Dig gravel until it drops flint");
            if (s.ironIngots() == 0) return new Step(Action.MINE_IRON, "", 1, Stage.FLINT, "Mine iron for flint and steel");
            return Step.craft("flint_and_steel", Stage.FLINT, "Craft flint and steel");
        }

        if (!s.portalFramed()) {
            if (s.cobble() < PORTAL_CORNERS) return new Step(Action.GATHER_STONE, "", PORTAL_CORNERS - s.cobble(), Stage.PORTAL, "Mine corner blocks for the frame");
            return Step.of(Action.BUILD_PORTAL, Stage.PORTAL, "Build the obsidian portal frame");
        }
        return Step.of(Action.IGNITE_PORTAL, Stage.PORTAL, "Light the portal with flint and steel");
    }

    /**
     * Blocks a cast frame still needs: about two per obsidian left to cast, for the corners and the mould behind
     * the frame that each pour is aimed against.
     */
    static int castBlocks(PortalRunSnapshot s) {
        return 2 * Math.max(0, PORTAL_OBSIDIAN - s.obsidian());
    }

    /** Sticks kept from torches and topped up near trees: those for the pickaxes still to make, and two spare pairs. */
    public static int sticksReserved(PortalRunSnapshot s, WorldFacts.Method frame) {
        return sticksKept(s, frame) + SPARE_STICKS;
    }

    /** Sticks kept for pickaxes still to make: the iron one, and the diamond one only when the frame is mined. */
    public static int sticksKept(PortalRunSnapshot s, WorldFacts.Method frame) {
        return (s.pickaxeTier() < 3 ? 2 : 0) + (frame == WorldFacts.Method.MINE_OBSIDIAN && s.pickaxeTier() < 4 ? 2 : 0);
    }

    /** Buckets the route needs: one for water when mining obsidian, a second for lava when casting it. */
    static int bucketsWanted(WorldFacts.Method frame) {
        return frame == WorldFacts.Method.CAST_OBSIDIAN ? 2 : 1;
    }

    /**
     * Iron still needed for whatever iron items are missing: pickaxe 3, three per bucket still wanted, chestplate 8,
     * flint and steel 1, and a shield's 1 when the wood for it is in hand.
     */
    static int ironShortfall(PortalRunSnapshot s, WorldFacts.Method frame) {
        int wanted = 0;
        if (s.pickaxeTier() < 3) wanted += 3;
        wanted += Math.max(0, bucketsWanted(frame) - s.emptyBuckets() - s.waterBuckets()) * 3;
        if (s.chestArmorTier() < 3 && s.pickaxeTier() < 4) wanted += 8;
        if (s.flintAndSteel() == 0) wanted += 1;
        if (s.shields() == 0 && s.woodUnits() >= SHIELD_PLANKS) wanted += 1; // Only with the wood for it in hand.
        return wanted;
    }

    /**
     * The step toward crafting {@code item}: planks and sticks first, then a reachable crafting table.
     * {@code planksNeeded} and {@code sticksNeeded} are the recipe's own wood inputs.
     */
    private static Step craftAtTable(PortalRunSnapshot s, String item, int planksNeeded, int sticksNeeded, Stage stage) {
        if (s.sticks() < sticksNeeded) {
            if (s.planks() >= 2) return Step.craft("stick", stage, "Craft sticks");
            if (s.logs() > 0) return Step.craft("planks", stage, "Craft planks");
            return new Step(Action.GATHER_LOGS, "", 2, stage, "Gather logs for sticks");
        }
        if (s.planks() < planksNeeded) {
            if (s.logs() > 0) return Step.craft("planks", stage, "Craft planks");
            return new Step(Action.GATHER_LOGS, "", 2, stage, "Gather logs for planks");
        }
        if (!s.tableNearby()) {
            if (s.craftingTables() > 0) return Step.of(Action.PLACE_TABLE, stage, "Place a crafting table");
            if (s.planks() >= 4) return Step.craft("crafting_table", stage, "Craft a crafting table");
            if (s.logs() > 0) return Step.craft("planks", stage, "Craft planks for a crafting table");
            return new Step(Action.GATHER_LOGS, "", 1, stage, "Gather logs for a crafting table");
        }
        return Step.craft(item, stage, "Craft " + item.replace('_', ' '));
    }
}
