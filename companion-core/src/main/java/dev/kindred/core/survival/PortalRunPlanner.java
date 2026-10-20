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
 * bucket and no diamonds at all. Where there is no ground to dig, as on a sky island, stone comes from a
 * cobblestone generator built from a bucket of lava and one of water, wood from saplings planted as the trees are
 * cut, and the chestplate kept for deep caves is not made.
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
     * Sticks kept beyond the pickaxes still to make: four replacements for ones that wear out, a log's worth.
     * Staircases and branch mines wear out a stone pickaxe every few minutes; live run 49 used three before its sixth
     * iron and was left at Y=19 with cobblestone, a table and no sticks, and run 53 used five before its third.
     */
    static final int SPARE_STICKS = 8;
    /**
     * Blocks dug for each iron ore found, a planning figure for the stick reserve: branch mining at the richest depth
     * took live run 58 about 800 blocks for 14 iron, and it ran out of sticks one iron short of the last pickaxe.
     */
    static final int BLOCKS_PER_IRON = 50;
    /** Blocks a stone pickaxe digs before it wears out. */
    static final int STONE_PICKAXE_USES = 131;
    public static final int DIAMONDS_NEEDED = 3;
    /**
     * Where there is no ground to dig, stone and leaves in sight count only this close: farther off they are likely
     * on another island across the void.
     */
    public static final int ISLAND_REACH = 8;
    /**
     * Planks kept back for another crafting table while there is work below ground, beyond the sticks: a table lost
     * or left behind down there cannot be replaced from trees (live run 57 left its table in a branch tunnel, then
     * wore out its last pickaxe at Y=54 with cobblestone and sticks in hand and no way to craft another).
     */
    static final int SPARE_TABLE_PLANKS = 4;
    /** Food items to carry before work takes the player away from animals. */
    public static final int FOOD_STOCK = 3;

    public enum Stage { WOOD, STONE, IRON, DIAMOND, OBSIDIAN, FLINT, PORTAL, DONE }

    public enum Action {
        DONE, COLLECT_SMELTED, HUNT, GATHER_LOGS, CRAFT, PLACE_TABLE, PLACE_FURNACE, GATHER_STONE,
        MINE_COAL, MINE_IRON, SMELT, AWAIT_SMELT, FILL_WATER, MINE_DIAMOND, MAKE_OBSIDIAN, GATHER_FLINT,
        BUILD_PORTAL, IGNITE_PORTAL,
        /** Cast the frame in place: lava poured into each frame cell and hardened with water. */
        CAST_PORTAL,
        /** Fill an empty bucket at a lava source. */
        FILL_LAVA,
        /** Build a cobblestone generator: a short channel where lava flows into water. */
        BUILD_GENERATOR,
        /** Plant a carried sapling where it can grow. */
        PLANT_SAPLING,
        /** Wait by planted saplings for one to grow into a tree. */
        AWAIT_TREE,
        /** Break leaves left after the logs for the saplings they drop. */
        GATHER_SAPLINGS
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
        // Where trees do not come back by themselves, every tree cut is replanted, as sky-island players do: saplings
        // go straight into the ground, and the leaves a cut tree leaves behind are broken for more before they decay.
        if (facts.can(WorldFacts.Method.REPLANT)) {
            if (s.saplings() > 0) {
                steps.add(new Step(Action.PLANT_SAPLING, "", s.saplings(), stage(s, facts), "Plant saplings to grow more wood"));
            } else if (!facts.knows(WorldFacts.Source.TREE) && !facts.knows(WorldFacts.Source.SAPLING)
                    && facts.distance(WorldFacts.Source.LEAVES).orElse(Integer.MAX_VALUE) <= ISLAND_REACH) {
                steps.add(Step.of(Action.GATHER_SAPLINGS, stage(s, facts), "Break the leaves left behind for saplings"));
            }
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
        if (s.pickaxeTier() == 2 || ironShortfall(s, frame, caves(facts)) > 0 && s.pickaxeTier() < 4) return Stage.IRON;
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
            if (s.cobble() >= 3) return craftAtTable(s, facts, "stone_pickaxe", 0, 2, Stage.WOOD);
            boolean haveTable = s.tableNearby() || s.craftingTables() > 0;
            int woodWanted = haveTable ? 6 : 12;
            if (s.woodUnits() < woodWanted) {
                return logs(facts, Math.max(1, (woodWanted - s.woodUnits() + 3) / 4), Stage.WOOD,
                        haveTable ? "Gather logs for a wooden pickaxe" : "Gather logs for a table and a wooden pickaxe");
            }
            return craftAtTable(s, facts, "wooden_pickaxe", 3, 2, Stage.WOOD);
        }
        // Stone: cobblestone for a stone pickaxe, sword, furnace and portal corners.
        if (s.pickaxeTier() == 1) {
            if (s.cobble() < 3) return stone(s, facts, 3 - s.cobble(), Stage.STONE, "Mine cobblestone for a stone pickaxe");
            return craftAtTable(s, facts, "stone_pickaxe", 0, 2, Stage.STONE);
        }
        if (s.swordTier() < 2 && s.cobble() >= 2) {
            return craftAtTable(s, facts, "stone_sword", 0, 1, Stage.STONE);
        }
        // Sticks for the pickaxes still to make and spares for ones that wear out, made while trees are in sight
        // or from wood already carried: there are none deep below, and a climb back up costs more than the sticks.
        boolean treesNear = facts.distance(WorldFacts.Source.TREE).orElse(Integer.MAX_VALUE) <= TREE_RANGE;
        // Packing for the work below, as players do before a trip down: what it will use, and a little over for
        // what can go wrong there (a table lost, a pickaxe wearing out halfway up a climb).
        boolean below = belowGroundWork(s, facts, frame);
        int tableWood = below ? SPARE_TABLE_PLANKS : 0;
        int spareWood = s.woodUnits() - (s.shields() == 0 ? SHIELD_PLANKS : 0) - tableWood;
        if (s.sticks() < sticksReserved(s, frame, caves(facts)) && (treesNear || spareWood >= 2)) {
            if (s.planks() >= 2 && spareWood >= 2) return Step.craft("stick", Stage.STONE, "Craft spare sticks for later pickaxes");
            if (s.logs() > 0 && spareWood >= 2) return Step.craft("planks", Stage.STONE, "Craft planks for spare sticks");
            return new Step(Action.GATHER_LOGS, "", 1, Stage.STONE, "Gather a log for spare sticks");
        }
        // Planks for a shield, taken while trees are in sight: arrows kill more players in caves than anything else.
        if (s.shields() == 0 && s.pickaxeTier() < 3 && s.woodUnits() < SHIELD_PLANKS
                && treesNear) {
            return new Step(Action.GATHER_LOGS, "", 2, Stage.STONE, "Gather logs for a shield");
        }
        // Kept until the pickaxe is final: diamond on the classic route, iron when the frame is cast. Not while no
        // more stone can be had (a limited world's few stone blocks used up, no generator yet): iron for the buckets
        // that build one comes first.
        if (s.cobble() < COBBLE_TARGET - 6 && s.pickaxeTier() < (frame == WorldFacts.Method.CAST_OBSIDIAN ? 3 : 4) && stoneAvailable(s, facts)) {
            return stone(s, facts, COBBLE_TARGET - s.cobble(), Stage.STONE, "Mine cobblestone for a furnace and portal corners");
        }

        // A spare pickaxe, made while it is cheap: one wearing out halfway up a climb never leaves the player with none.
        if (below && s.pickaxes() < 2 && s.cobble() >= 3) {
            return craftAtTable(s, facts, "stone_pickaxe", 0, 2, Stage.STONE, "Craft a spare stone pickaxe for the work below");
        }

        // Iron: pickaxe, bucket and flint and steel, smelted in an owned furnace.
        boolean caves = caves(facts);
        int ironWanted = ironShortfall(s, frame, caves);
        int ironHave = s.ironIngots() + s.rawIron();
        // With a shield's wood in hand, the first iron is smelted and made into one at once, as players do: the caves
        // where the rest of the iron is found are where skeletons end most runs.
        boolean shieldFirst = s.shields() == 0 && s.woodUnits() >= SHIELD_PLANKS;
        // Then the iron pickaxe as soon as there is iron for it: it lasts twice as long as stone, and staircases and
        // branch mines wear a stone one out every few minutes (live run 50 used six in one life before its iron).
        boolean pickaxeFirst = !shieldFirst && s.pickaxeTier() < 3 && ironHave >= 3;
        if (shieldFirst && s.ironIngots() >= 1) return craftAtTable(s, facts, "shield", SHIELD_PLANKS, 0, Stage.IRON);
        // Wood for another table, should the carried one be lost below, taken while trees are in sight.
        if (below && s.woodUnits() < tableWood + (s.shields() == 0 ? SHIELD_PLANKS : 0) && treesNear) {
            return new Step(Action.GATHER_LOGS, "", 1, Stage.STONE, "Gather a log for a spare crafting table");
        }
        if (ironHave < ironWanted && !(shieldFirst && s.rawIron() > 0) && !pickaxeFirst) {
            return new Step(Action.MINE_IRON, "", ironWanted - ironHave, Stage.IRON, "Mine iron ore (" + ironHave + "/" + ironWanted + ")");
        }
        if (s.rawIron() > 0) {
            if (s.furnaceSmelting()) return Step.of(Action.AWAIT_SMELT, Stage.IRON, "Wait for the furnace to smelt iron");
            // Wood kept for a shield is not fuel.
            int fuelWood = (s.rawIron() * 2 + 2) / 3;
            if (s.coal() == 0 && spareWood < fuelWood) {
                // Where there is no coal and wood is grown, the fuel is wood.
                if (facts.can(WorldFacts.Method.REPLANT) && !facts.knows(WorldFacts.Source.COAL_ORE)) {
                    return logs(facts, (fuelWood - spareWood + 3) / 4, Stage.IRON, "Gather logs for fuel to smelt iron");
                }
                return new Step(Action.MINE_COAL, "", 1, Stage.IRON, "Find fuel to smelt iron");
            }
            if (!s.furnaceNearby() && s.furnaces() == 0) {
                if (s.cobble() < 8) return stone(s, facts, 8 - s.cobble(), Stage.IRON, "Mine cobblestone for a furnace");
                return craftAtTable(s, facts, "furnace", 0, 0, Stage.IRON);
            }
            return new Step(Action.SMELT, "raw_iron", s.rawIron(), Stage.IRON, "Smelt raw iron");
        }
        if (s.pickaxeTier() < 3) return craftAtTable(s, facts, "iron_pickaxe", 0, 2, Stage.IRON);
        if (s.emptyBuckets() + s.waterBuckets() < bucketsWanted(frame)) return craftAtTable(s, facts, "bucket", 0, 0, Stage.IRON);
        // Caves below are full of skeletons and zombies: an iron chestplate before going deep.
        if (caves && s.chestArmorTier() < 3 && s.pickaxeTier() < 4) return craftAtTable(s, facts, "iron_chestplate", 0, 0, Stage.IRON);

        // Diamonds for a pickaxe that can mine obsidian, unless the frame is cast instead.
        if (frame == WorldFacts.Method.MINE_OBSIDIAN && s.pickaxeTier() < 4) {
            if (s.diamonds() < DIAMONDS_NEEDED) {
                return new Step(Action.MINE_DIAMOND, "", DIAMONDS_NEEDED - s.diamonds(), Stage.DIAMOND, "Strip-mine for diamonds (" + s.diamonds() + "/3)");
            }
            return craftAtTable(s, facts, "diamond_pickaxe", 0, 2, Stage.DIAMOND);
        }

        // Cast: the frame hardens in place from lava poured into it and water poured over it.
        if (frame == WorldFacts.Method.CAST_OBSIDIAN && !s.portalFramed() && s.obsidian() < PORTAL_OBSIDIAN) {
            if (s.waterBuckets() == 0) return Step.of(Action.FILL_WATER, Stage.OBSIDIAN, "Fill a bucket with water");
            int blocks = castBlocks(s);
            if (s.cobble() < blocks) return stone(s, facts, blocks - s.cobble(), Stage.OBSIDIAN, "Mine blocks for the casting mould");
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
            if (s.cobble() < PORTAL_CORNERS) return stone(s, facts, PORTAL_CORNERS - s.cobble(), Stage.PORTAL, "Mine corner blocks for the frame");
            return Step.of(Action.BUILD_PORTAL, Stage.PORTAL, "Build the obsidian portal frame");
        }
        return Step.of(Action.IGNITE_PORTAL, Stage.PORTAL, "Light the portal with flint and steel");
    }

    /**
     * The step toward {@code count} more cobblestone. Stone in sight or ground to dig is the ordinary way. With
     * neither, as on a sky island, a cobblestone generator: lava flowing into water in a short channel, mined again
     * and again, built from a bucket of each (water first, so the lava never meets a water source).
     */
    private static Step stone(PortalRunSnapshot s, WorldFacts facts, int count, Stage stage, String why) {
        if (stoneNear(facts) || facts.knows(WorldFacts.Source.GENERATOR) || !facts.can(WorldFacts.Method.COBBLE_GENERATOR)) {
            return new Step(Action.GATHER_STONE, "", count, stage, why);
        }
        int empty = s.emptyBuckets() - s.lavaBuckets();
        if (s.waterBuckets() == 0) {
            if (empty > 0 && facts.knows(WorldFacts.Source.WATER)) return Step.of(Action.FILL_WATER, stage, "Fill a bucket with water for a cobblestone generator");
        } else if (s.lavaBuckets() == 0) {
            if (empty > 0 && facts.knows(WorldFacts.Source.LAVA)) return Step.of(Action.FILL_LAVA, stage, "Fill a bucket with lava for a cobblestone generator");
        } else {
            return Step.of(Action.BUILD_GENERATOR, stage, "Build a cobblestone generator from lava and water");
        }
        return new Step(Action.GATHER_STONE, "", count, stage, why); // Nothing to build one with yet: look for stone.
    }

    /**
     * Some way to more stone right now: stone in sight, ground to dig, a generator built, or the buckets (or the
     * empty buckets and the sources) to build one.
     */
    static boolean stoneAvailable(PortalRunSnapshot s, WorldFacts facts) {
        if (stoneNear(facts) || facts.knows(WorldFacts.Source.GENERATOR)) return true;
        if (!facts.can(WorldFacts.Method.COBBLE_GENERATOR)) return true; // Nothing else to try: look for it, as ever.
        int empty = s.emptyBuckets() - s.lavaBuckets();
        boolean water = s.waterBuckets() > 0 || empty > 0 && facts.knows(WorldFacts.Source.WATER);
        boolean lava = s.lavaBuckets() > 0 || empty > (s.waterBuckets() > 0 ? 0 : 1) && facts.knows(WorldFacts.Source.LAVA);
        return water && lava;
    }

    /**
     * The step toward {@code count} more logs: trees in sight, else, where trees grow only from what is planted,
     * a wait by the saplings already in the ground.
     */
    private static Step logs(WorldFacts facts, int count, Stage stage, String why) {
        if (!facts.knows(WorldFacts.Source.TREE) && facts.can(WorldFacts.Method.REPLANT) && facts.knows(WorldFacts.Source.SAPLING)) {
            return new Step(Action.AWAIT_TREE, "", count, stage, "Wait for a planted sapling to grow: " + why);
        }
        return new Step(Action.GATHER_LOGS, "", count, stage, why);
    }

    /** Ground below to dig, or, where there is none, stone in sight close by. */
    private static boolean stoneNear(WorldFacts facts) {
        if (facts.can(WorldFacts.Method.DIG_FOR_STONE)) return true;
        return facts.distance(WorldFacts.Source.STONE).orElse(Integer.MAX_VALUE) <= ISLAND_REACH;
    }

    /**
     * Mining ahead below ground: where there is ground to dig, from the stone pickaxe until the final one (diamond
     * on the classic route, iron when the frame is cast) is in hand.
     */
    static boolean belowGroundWork(PortalRunSnapshot s, WorldFacts facts, WorldFacts.Method frame) {
        return caves(facts) && s.pickaxeTier() >= 2 && s.pickaxeTier() < (frame == WorldFacts.Method.CAST_OBSIDIAN ? 3 : 4);
    }

    /** Ground below to dig into, and so caves full of mobs: a sky island has neither. */
    private static boolean caves(WorldFacts facts) {
        return facts.can(WorldFacts.Method.DIG_FOR_STONE);
    }

    /**
     * Blocks a cast frame still needs: about two per obsidian left to cast, for the corners and the mould behind
     * the frame that each pour is aimed against.
     */
    static int castBlocks(PortalRunSnapshot s) {
        return 2 * Math.max(0, PORTAL_OBSIDIAN - s.obsidian());
    }

    /** {@link #sticksReserved(PortalRunSnapshot, WorldFacts.Method, boolean)} where there are caves to dig in. */
    public static int sticksReserved(PortalRunSnapshot s, WorldFacts.Method frame) {
        return sticksReserved(s, frame, true);
    }

    /**
     * Sticks kept from torches and topped up near trees: those for the pickaxes still to make, and spares sized to the
     * digging still ahead, as a player packs for a trip down. Where there is ground to dig, two for each stone pickaxe
     * the iron still wanted will wear out, at about {@link #BLOCKS_PER_IRON} blocks an iron, and a pair over; never
     * fewer than four spare pairs.
     */
    public static int sticksReserved(PortalRunSnapshot s, WorldFacts.Method frame, boolean caves) {
        int spares = SPARE_STICKS;
        if (caves) {
            int iron = Math.max(0, ironShortfall(s, frame, true) - s.ironIngots() - s.rawIron());
            int pickaxes = (iron * BLOCKS_PER_IRON + STONE_PICKAXE_USES - 1) / STONE_PICKAXE_USES + 1;
            spares = Math.max(SPARE_STICKS, 2 * pickaxes);
        }
        return sticksKept(s, frame) + spares;
    }

    /** Planks held back from sticks, torches and fuel: a shield's still to make, and a spare table's for the work below. */
    public static int woodKept(PortalRunSnapshot s, WorldFacts facts) {
        return (s.shields() == 0 ? SHIELD_PLANKS : 0) + (belowGroundWork(s, facts, frameMethod(s, facts)) ? SPARE_TABLE_PLANKS : 0);
    }

    /** Sticks kept for pickaxes still to make: the iron one, and the diamond one only when the frame is mined. */
    public static int sticksKept(PortalRunSnapshot s, WorldFacts.Method frame) {
        return (s.pickaxeTier() < 3 ? 2 : 0) + (frame == WorldFacts.Method.MINE_OBSIDIAN && s.pickaxeTier() < 4 ? 2 : 0);
    }

    /** Buckets the route needs: one for water when mining obsidian, a second for lava when casting it. */
    static int bucketsWanted(WorldFacts.Method frame) {
        return frame == WorldFacts.Method.CAST_OBSIDIAN ? 2 : 1;
    }

    /** {@link #ironShortfall(PortalRunSnapshot, WorldFacts.Method, boolean)} where there are caves to go down into. */
    static int ironShortfall(PortalRunSnapshot s, WorldFacts.Method frame) {
        return ironShortfall(s, frame, true);
    }

    /**
     * Iron still needed for whatever iron items are missing: pickaxe 3, three per bucket still wanted, chestplate 8
     * where there are caves to go down into, flint and steel 1, and a shield's 1 when the wood for it is in hand.
     */
    static int ironShortfall(PortalRunSnapshot s, WorldFacts.Method frame, boolean caves) {
        int wanted = 0;
        if (s.pickaxeTier() < 3) wanted += 3;
        wanted += Math.max(0, bucketsWanted(frame) - s.emptyBuckets() - s.waterBuckets()) * 3;
        if (caves && s.chestArmorTier() < 3 && s.pickaxeTier() < 4) wanted += 8;
        if (s.flintAndSteel() == 0) wanted += 1;
        if (s.shields() == 0 && s.woodUnits() >= SHIELD_PLANKS) wanted += 1; // Only with the wood for it in hand.
        return wanted;
    }

    /**
     * The step toward crafting {@code item}: planks and sticks first, then a reachable crafting table.
     * {@code planksNeeded} and {@code sticksNeeded} are the recipe's own wood inputs.
     */
    private static Step craftAtTable(PortalRunSnapshot s, WorldFacts facts, String item, int planksNeeded, int sticksNeeded, Stage stage) {
        return craftAtTable(s, facts, item, planksNeeded, sticksNeeded, stage, "Craft " + item.replace('_', ' '));
    }

    private static Step craftAtTable(PortalRunSnapshot s, WorldFacts facts, String item, int planksNeeded, int sticksNeeded, Stage stage, String why) {
        if (s.sticks() < sticksNeeded) {
            if (s.planks() >= 2) return Step.craft("stick", stage, "Craft sticks");
            if (s.logs() > 0) return Step.craft("planks", stage, "Craft planks");
            return logs(facts, 2, stage, "Gather logs for sticks");
        }
        if (s.planks() < planksNeeded) {
            if (s.logs() > 0) return Step.craft("planks", stage, "Craft planks");
            return logs(facts, 2, stage, "Gather logs for planks");
        }
        if (!s.tableNearby()) {
            if (s.craftingTables() > 0) return Step.of(Action.PLACE_TABLE, stage, "Place a crafting table");
            if (s.planks() >= 4) return Step.craft("crafting_table", stage, "Craft a crafting table");
            if (s.logs() > 0) return Step.craft("planks", stage, "Craft planks for a crafting table");
            return logs(facts, 1, stage, "Gather logs for a crafting table");
        }
        return Step.craft(item, stage, why);
    }
}
