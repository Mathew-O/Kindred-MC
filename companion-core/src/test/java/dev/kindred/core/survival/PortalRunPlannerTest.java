package dev.kindred.core.survival;

import dev.kindred.core.survival.PortalRunPlanner.Action;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRunPlannerTest {

    /** Mutable stand-in for a player's inventory that applies each step's vanilla outcome. */
    private static final class Sim {
        int logs, planks, sticks, cobble, coal, rawIron, iron, diamonds, flint, gravel, obsidian, buckets, lava, water, fns,
                tables, furnaces, saplings, food = 5, pick, pickaxes, sword, chest, shields, furnaceInput, furnaceOutput, foodLevel = 20;
        boolean tableNearby, furnaceNearby, framed, lit;
        /** A sky island: no ground to dig, logs only from standing trees, which grow only from planted saplings. */
        boolean sky, generator;
        int treeLogs, leaves, planted, stoneLeft;

        PortalRunSnapshot snapshot() {
            return new PortalRunSnapshot(logs, planks, sticks, cobble, coal, rawIron + furnaceInput, iron + furnaceOutput, diamonds,
                    flint, gravel, obsidian, buckets, lava, water, fns, tables, furnaces, saplings, food, pick, Math.max(pickaxes, pick >= 2 ? 1 : 0), sword, chest, shields,
                    tableNearby, furnaceNearby, furnaceInput > 0, furnaceOutput, foodLevel, framed, lit);
        }

        /** What a player on the sky island sees: its tree while it stands, leaves, its saplings and generator, the ore islands. */
        WorldFacts skyFacts() {
            var near = new java.util.EnumMap<WorldFacts.Source, Integer>(WorldFacts.Source.class);
            if (treeLogs > 0) near.put(WorldFacts.Source.TREE, 6);
            if (leaves > 0) near.put(WorldFacts.Source.LEAVES, 6);
            if (planted > 0) near.put(WorldFacts.Source.SAPLING, 4);
            if (generator) near.put(WorldFacts.Source.GENERATOR, 3);
            if (stoneLeft > 0) near.put(WorldFacts.Source.STONE, 5);
            near.put(WorldFacts.Source.IRON_ORE, 20);
            near.put(WorldFacts.Source.LAVA, 10);
            near.put(WorldFacts.Source.WATER, 8);
            near.put(WorldFacts.Source.GRAVEL, 12);
            return new WorldFacts(near, SKY.methods());
        }

        void apply(PortalRunPlanner.Step step) {
            switch (step.action()) {
                case GATHER_LOGS -> {
                    if (sky) { assertTrue(treeLogs > 0, "no tree left to cut"); treeLogs--; }
                    logs++;
                }
                case GATHER_STONE -> {
                    assertTrue(pick >= 1, "stone needs a pickaxe");
                    if (sky && stoneLeft > 0) stoneLeft--;
                    else assertTrue(!sky || generator, "a sky island has no stone but its few blocks and its generator's");
                    cobble++;
                }
                case MINE_COAL -> { assertTrue(!sky, "a sky island has no coal"); coal++; }
                case FILL_LAVA -> { assertTrue(buckets - lava > 0, "an empty bucket"); lava++; }
                case BUILD_GENERATOR -> {
                    assertTrue(lava > 0 && water > 0, "a bucket of lava and one of water");
                    lava--; water--; buckets++; // Both buckets come back empty.
                    generator = true;
                }
                case GATHER_SAPLINGS -> { assertTrue(leaves > 0, "leaves to break"); leaves--; saplings++; }
                case PLANT_SAPLING -> { assertTrue(saplings > 0); saplings--; planted++; }
                case AWAIT_TREE -> { assertTrue(planted > 0, "something planted to wait for"); planted--; treeLogs += 5; leaves += 2; }
                case MINE_IRON -> { assertTrue(pick >= 2, "iron needs a stone pickaxe"); rawIron++; }
                case MINE_DIAMOND -> { assertTrue(pick >= 3, "diamonds need an iron pickaxe"); diamonds++; }
                case PLACE_TABLE -> { tables--; tableNearby = true; }
                case SMELT -> {
                    if (!furnaceNearby) { assertTrue(furnaces > 0); furnaces--; furnaceNearby = true; return; }
                    int fuelItems = coal > 0 ? 1 : (rawIron * 2 + 2) / 3;
                    if (coal > 0) coal--; else planks -= Math.min(planks, fuelItems);
                    furnaceInput += rawIron;
                    rawIron = 0;
                }
                case AWAIT_SMELT -> { furnaceOutput += furnaceInput; furnaceInput = 0; }
                case COLLECT_SMELTED -> { iron += furnaceOutput; furnaceOutput = 0; }
                case FILL_WATER -> { assertTrue(buckets - lava > 0, "an empty bucket"); buckets--; water++; }
                case MAKE_OBSIDIAN -> { assertTrue(pick >= 4 && water > 0); obsidian++; }
                case GATHER_FLINT -> flint++;
                case BUILD_PORTAL -> { assertTrue(obsidian >= 10 && cobble >= 4); obsidian -= 10; cobble -= 4; framed = true; }
                case IGNITE_PORTAL -> { assertTrue(fns > 0 && framed); lit = true; }
                case CAST_PORTAL -> { assertTrue(buckets + water >= 2 && water > 0, "casting needs water and a second bucket for lava"); framed = true; }
                case CRAFT -> craft(step.item());
                default -> throw new AssertionError("Unexpected step " + step);
            }
        }

        void craft(String item) {
            switch (item) {
                case "planks" -> { assertTrue(logs > 0); logs--; planks += 4; }
                case "stick" -> { assertTrue(planks >= 2); planks -= 2; sticks += 4; }
                case "crafting_table" -> { assertTrue(planks >= 4); planks -= 4; tables++; }
                case "wooden_pickaxe" -> { need(3, 2, 0); pick = 1; }
                case "stone_pickaxe" -> { need(0, 2, 3); pickaxes = Math.max(pickaxes, pick >= 2 ? 1 : 0) + 1; pick = Math.max(pick, 2); }
                case "stone_sword" -> { need(0, 1, 2); sword = 2; }
                case "furnace" -> { need(0, 0, 8); furnaces++; }
                case "iron_pickaxe" -> { needIron(3); sticks -= 2; pickaxes = Math.max(pickaxes, pick >= 2 ? 1 : 0) + 1; pick = 3; }
                case "bucket" -> { needIron(3); buckets++; }
                case "iron_chestplate" -> { needIron(8); chest = 3; }
                case "diamond_pickaxe" -> { assertTrue(diamonds >= 3 && sticks >= 2 && tableNearby); diamonds -= 3; sticks -= 2; pickaxes = Math.max(pickaxes, pick >= 2 ? 1 : 0) + 1; pick = 4; }
                case "flint_and_steel" -> { assertTrue(flint > 0 && iron > 0); flint--; iron--; fns++; }
                case "shield" -> { need(6, 0, 0); needIron(1); shields++; }
                default -> throw new AssertionError("Unexpected craft " + item);
            }
        }

        void need(int p, int s, int c) {
            assertTrue(planks >= p && sticks >= s && cobble >= c && tableNearby, "missing inputs");
            planks -= p; sticks -= s; cobble -= c;
        }

        void needIron(int n) {
            assertTrue(iron >= n && tableNearby, "missing iron or table");
            iron -= n;
        }
    }

    @Test
    void walksTheWholeRouteFromAnEmptyInventoryToALitPortal() {
        Sim sim = new Sim();
        List<Action> seen = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            // Like the adapter: the first step it can act on; hunting needs an animal in sight, so skip it here.
            var step = PortalRunPlanner.plan(sim.snapshot()).stream()
                    .filter(candidate -> candidate.action() != Action.HUNT).findFirst().orElseThrow();
            if (step.action() == Action.DONE) {
                assertTrue(sim.lit);
                assertTrue(seen.contains(Action.MAKE_OBSIDIAN) && seen.contains(Action.GATHER_FLINT));
                return;
            }
            seen.add(step.action());
            sim.apply(step);
        }
        throw new AssertionError("Planner did not reach a lit portal: " + seen.subList(Math.max(0, seen.size() - 20), seen.size()));
    }

    @Test
    void firstStepFromNothingIsGatheringLogs() {
        var step = PortalRunPlanner.plan(new Sim().snapshot()).getLast();
        assertEquals(Action.GATHER_LOGS, step.action());
        assertEquals(PortalRunPlanner.Stage.WOOD, step.stage());
    }

    @Test
    void aWornOutPickaxeWithCobblestoneAtHandGoesStraightBackToStone() {
        Sim sim = new Sim();
        sim.cobble = 5;
        sim.sticks = 2;
        sim.tables = 1;
        var step = PortalRunPlanner.plan(sim.snapshot()).getLast();
        assertEquals(Action.PLACE_TABLE, step.action(), "the carried table comes out first");
        sim.apply(step);
        step = PortalRunPlanner.plan(sim.snapshot()).getLast();
        assertEquals(Action.CRAFT, step.action());
        assertEquals("stone_pickaxe", step.item(), "no detour through logs and a wooden pickaxe");
    }

    @Test
    void withATableAlreadyOwnedAWoodenPickaxeNeedsFewerLogs() {
        Sim sim = new Sim();
        sim.logs = 2; // Eight planks' worth: enough for a pickaxe and sticks, not for a table as well.
        sim.tableNearby = true;
        var step = PortalRunPlanner.plan(sim.snapshot()).getLast();
        assertEquals(Action.CRAFT, step.action());
        assertEquals("planks", step.item());
    }

    @Test
    void aShortFoodStockAsksForAHuntWithoutDisplacingTheRoute() {
        Sim sim = new Sim();
        sim.food = 1;
        var steps = PortalRunPlanner.plan(sim.snapshot());
        assertEquals(Action.HUNT, steps.getFirst().action());
        assertEquals(2, steps.getFirst().count());
        assertEquals(Action.GATHER_LOGS, steps.getLast().action(), "the route step is still offered when no animal is in sight");
    }

    @Test
    void hungerWithoutFoodPutsHuntingFirstAndSmeltedOutputIsCollected() {
        Sim sim = new Sim();
        sim.food = 0;
        sim.foodLevel = 10;
        sim.furnaceNearby = true;
        sim.furnaceOutput = 3;
        var steps = PortalRunPlanner.plan(sim.snapshot());
        assertEquals(Action.COLLECT_SMELTED, steps.get(0).action());
        assertEquals(Action.HUNT, steps.get(1).action());
    }

    /** Both frame methods available in an ordinary world, nothing seen yet. */
    private static final WorldFacts BOTH = new WorldFacts(java.util.Map.of(),
            java.util.Set.of(WorldFacts.Method.MINE_OBSIDIAN, WorldFacts.Method.CAST_OBSIDIAN, WorldFacts.Method.DIG_FOR_STONE));

    /** A sky island's methods: no ground to dig, so a generator for stone and replanting for wood. */
    private static final WorldFacts SKY = new WorldFacts(java.util.Map.of(), java.util.Set.of(WorldFacts.Method.MINE_OBSIDIAN,
            WorldFacts.Method.CAST_OBSIDIAN, WorldFacts.Method.COBBLE_GENERATOR, WorldFacts.Method.REPLANT));

    /** The classic sky island start: one tree, and the starter chest's bucket of lava and bucket of water. */
    private static Sim skyIsland() {
        Sim sim = new Sim();
        sim.sky = true;
        sim.treeLogs = 5;
        sim.leaves = 2;
        sim.buckets = 1; // Holding lava.
        sim.lava = 1;
        sim.water = 1;
        return sim;
    }

    @Test
    void onASkyIslandTheWholeRouteRunsOnAGeneratorAndReplantedTrees() {
        Sim sim = skyIsland();
        List<Action> seen = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            var step = PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).stream()
                    .filter(candidate -> candidate.action() != Action.HUNT).findFirst().orElseThrow();
            if (step.action() == Action.DONE) {
                assertTrue(sim.lit);
                assertTrue(seen.contains(Action.BUILD_GENERATOR), "stone came from a generator");
                assertTrue(seen.contains(Action.PLANT_SAPLING) && seen.contains(Action.AWAIT_TREE), "wood came from replanted trees");
                assertTrue(seen.contains(Action.CAST_PORTAL) && !seen.contains(Action.MINE_DIAMOND), "the frame was cast");
                assertEquals(0, sim.chest, "no chestplate where there are no caves");
                return;
            }
            seen.add(step.action());
            sim.apply(step);
        }
        throw new AssertionError("Planner did not reach a lit portal on the sky island: " + seen.subList(Math.max(0, seen.size() - 20), seen.size()));
    }

    @Test
    void aLimitedIslandWithoutStarterBucketsGetsItsIronBeforeItsStoneRunsOut() {
        // Sixteen stone blocks and no starter chest: the stockpile waits until iron has made the buckets for a generator.
        Sim sim = skyIsland();
        sim.buckets = 0; sim.lava = 0; sim.water = 0; sim.stoneLeft = 16;
        List<Action> seen = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            var step = PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).stream()
                    .filter(candidate -> candidate.action() != Action.HUNT).findFirst().orElseThrow();
            if (step.action() == Action.DONE) {
                assertTrue(sim.lit);
                assertTrue(seen.contains(Action.FILL_LAVA) && seen.contains(Action.BUILD_GENERATOR), "a generator from buckets it made");
                return;
            }
            seen.add(step.action());
            sim.apply(step);
        }
        throw new AssertionError("Planner did not reach a lit portal on the limited island: " + seen.subList(Math.max(0, seen.size() - 20), seen.size()));
    }

    @Test
    void stoneComesFromAGeneratorOnlyWhereThereIsNoGroundToDig() {
        Sim sim = skyIsland();
        sim.pick = 1; sim.tableNearby = true; sim.treeLogs = 0;
        assertEquals(Action.BUILD_GENERATOR, PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getLast().action(),
                "no stone to dig: the starter buckets become a generator");
        var ordinary = new WorldFacts(java.util.Map.of(), java.util.Set.of(WorldFacts.Method.MINE_OBSIDIAN, WorldFacts.Method.DIG_FOR_STONE,
                WorldFacts.Method.COBBLE_GENERATOR));
        assertEquals(Action.GATHER_STONE, PortalRunPlanner.plan(sim.snapshot(), ordinary).getLast().action(),
                "with ground below, stone is dug even with both buckets in hand");
        sim.apply(PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getLast());
        assertEquals(Action.GATHER_STONE, PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getLast().action(), "then it is mined");

        Sim empty = skyIsland();
        empty.pick = 1; empty.tableNearby = true; empty.lava = 0; empty.water = 0; empty.buckets = 2;
        assertEquals(Action.FILL_WATER, PortalRunPlanner.plan(empty.snapshot(), empty.skyFacts()).getLast().action(), "water first");
        empty.buckets = 1; empty.water = 1;
        assertEquals(Action.FILL_LAVA, PortalRunPlanner.plan(empty.snapshot(), empty.skyFacts()).getLast().action(), "then lava");
    }

    @Test
    void cutTreesAreReplantedWhereTreesDoNotComeBack() {
        Sim sim = skyIsland();
        sim.treeLogs = 0;
        var steps = PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts());
        assertEquals(Action.GATHER_SAPLINGS, steps.get(steps.size() - 2).action(), "the cut tree's leaves are broken for saplings");
        sim.apply(steps.get(steps.size() - 2));
        assertEquals(Action.PLANT_SAPLING, PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getFirst().action());
        sim.apply(PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getFirst());
        sim.leaves = 0;
        var wood = PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getLast();
        assertEquals(Action.AWAIT_TREE, wood.action(), "wood now grows from what was planted");
        sim.saplings = 1;
        assertEquals(Action.GATHER_LOGS, PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).getLast().action(),
                "in an ordinary world it looks for another tree instead");
        assertTrue(PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).stream().noneMatch(step -> step.action() == Action.PLANT_SAPLING),
                "and carries its saplings rather than plant them");
    }

    @Test
    void theChestplateIsForCavesASkyIslandHasNone() {
        Sim sim = new Sim();
        sim.pick = 3; sim.buckets = 2; sim.fns = 1; sim.shields = 1;
        assertEquals(8, PortalRunPlanner.ironShortfall(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN, true));
        assertEquals(0, PortalRunPlanner.ironShortfall(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN, false));
    }

    @Test
    void withCastingAvailableTheRouteSkipsDiamondsAndCastsTheFrame() {
        Sim sim = new Sim();
        List<Action> seen = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            var step = PortalRunPlanner.plan(sim.snapshot(), BOTH).stream()
                    .filter(candidate -> candidate.action() != Action.HUNT).findFirst().orElseThrow();
            if (step.action() == Action.DONE) {
                assertTrue(sim.lit);
                assertTrue(seen.contains(Action.CAST_PORTAL), "the frame was cast");
                assertTrue(!seen.contains(Action.MINE_DIAMOND) && !seen.contains(Action.MAKE_OBSIDIAN), "no diamonds, nothing mined");
                assertEquals(2, sim.buckets + sim.water, "a second bucket for the lava");
                return;
            }
            seen.add(step.action());
            sim.apply(step);
        }
        throw new AssertionError("Planner did not reach a lit portal: " + seen.subList(Math.max(0, seen.size() - 20), seen.size()));
    }

    /** Iron work done: iron pickaxe, one bucket, chestplate, flint and steel, table at hand. */
    private static Sim ironDone() {
        Sim sim = new Sim();
        sim.pick = 3; sim.pickaxes = 2; sim.sword = 2; sim.chest = 3; sim.shields = 1; sim.buckets = 1; sim.fns = 1; sim.cobble = 24; sim.sticks = 4;
        sim.tableNearby = true; sim.furnaceNearby = true;
        return sim;
    }

    @Test
    void beforeTheWorkBelowItPacksASparePickaxeAndATablesWorthOfWood() {
        // Live run 57 left its table in a branch tunnel and wore out its last pickaxe at Y=54, with no way to make another.
        Sim sim = new Sim();
        // The reserve's eighteen sticks and the spare's two (sticks used for a spare are topped up like any others).
        sim.pick = 2; sim.pickaxes = 1; sim.sword = 2; sim.cobble = 24; sim.sticks = 20; sim.shields = 1; sim.tableNearby = true;
        var trees = new WorldFacts(java.util.Map.of(WorldFacts.Source.TREE, 10), WorldFacts.ordinary().methods());
        var step = PortalRunPlanner.plan(sim.snapshot(), trees).getLast();
        assertEquals("stone_pickaxe", step.item(), "a spare pickaxe, made while it is cheap");
        assertTrue(step.why().contains("spare"));
        sim.apply(step);
        assertEquals(2, sim.snapshot().pickaxes());
        assertEquals("Gather a log for a spare crafting table", PortalRunPlanner.plan(sim.snapshot(), trees).getLast().why(),
                "a table's worth of wood while the trees are in sight");
        sim.logs = 1;
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), trees).getLast().action(), "packed: on to the iron");
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).getLast().action(),
                "below ground with no trees in sight, no trip back up for it");

        // More food before the work below: three more than the usual stock, taken while animals are in sight.
        sim.food = 4;
        var hunt = PortalRunPlanner.plan(sim.snapshot(), trees).stream().filter(candidate -> candidate.action() == Action.HUNT).findFirst();
        assertTrue(hunt.isPresent() && hunt.get().count() == 2, "four of six: " + hunt);
        sim.food = 6;
        assertTrue(PortalRunPlanner.plan(sim.snapshot(), trees).stream().noneMatch(candidate -> candidate.action() == Action.HUNT));

        // The table's wood is not burnt as fuel.
        sim.logs = 0; sim.planks = 4; sim.rawIron = 3; sim.furnaceNearby = true;
        assertEquals(Action.MINE_COAL, PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).getLast().action());

        // Nothing to pack for where there is no ground below.
        Sim sky = skyIsland();
        sky.pick = 2; sky.pickaxes = 1; sky.sword = 2; sky.cobble = 24; sky.sticks = 12; sky.shields = 1; sky.tableNearby = true;
        assertNotEquals("stone_pickaxe", PortalRunPlanner.plan(sky.snapshot(), sky.skyFacts()).getLast().item());
    }

    @Test
    void theFrameMethodFollowsWhatThePlayerHasAndKnows() {
        Sim sim = ironDone();
        assertEquals(WorldFacts.Method.MINE_OBSIDIAN, PortalRunPlanner.frameMethod(sim.snapshot(), WorldFacts.ordinary()),
                "only the classic route when casting is not available");
        assertEquals(WorldFacts.Method.CAST_OBSIDIAN, PortalRunPlanner.frameMethod(sim.snapshot(), BOTH),
                "a second bucket is cheaper than a diamond search");
        assertEquals(PortalRunPlanner.Stage.IRON, PortalRunPlanner.stage(sim.snapshot(), BOTH), "three more iron for that bucket");

        sim.diamonds = 3;
        assertEquals(WorldFacts.Method.MINE_OBSIDIAN, PortalRunPlanner.frameMethod(sim.snapshot(), BOTH),
                "with the diamonds in hand the pickaxe is cheaper than another bucket");
        sim.diamonds = 0;
        sim.pick = 4;
        assertEquals(WorldFacts.Method.MINE_OBSIDIAN, PortalRunPlanner.frameMethod(sim.snapshot(), BOTH), "a diamond pickaxe already owned");
    }

    @Test
    void theChoiceChangesAsSoonAsTheWorldDoes() {
        Sim sim = ironDone();
        sim.diamonds = 2;
        assertEquals(WorldFacts.Method.CAST_OBSIDIAN, PortalRunPlanner.frameMethod(sim.snapshot(), BOTH),
                "one more diamond somewhere unseen is a long search");
        var seenDiamond = new WorldFacts(java.util.Map.of(WorldFacts.Source.DIAMOND_ORE, 9), BOTH.methods());
        assertEquals(WorldFacts.Method.MINE_OBSIDIAN, PortalRunPlanner.frameMethod(sim.snapshot(), seenDiamond),
                "diamond ore in sight makes the third diamond cheap");
        assertEquals(Action.MINE_DIAMOND, PortalRunPlanner.plan(sim.snapshot(), seenDiamond).getLast().action());
    }

    @Test
    void castingWithTwoBucketsGoesStraightToTheFrameAfterIron() {
        Sim sim = ironDone();
        sim.buckets = 2;
        assertEquals(PortalRunPlanner.Stage.OBSIDIAN, PortalRunPlanner.stage(sim.snapshot(), BOTH), "no diamond stage when casting");
        assertEquals(Action.FILL_WATER, PortalRunPlanner.plan(sim.snapshot(), BOTH).getLast().action());
        sim.buckets = 1;
        sim.water = 1;
        assertEquals(Action.CAST_PORTAL, PortalRunPlanner.plan(sim.snapshot(), BOTH).getLast().action());
    }

    @Test
    void castingKeepsBlocksForTheMouldAndNoMoreThanItNeeds() {
        Sim sim = ironDone();
        sim.buckets = 1;
        sim.water = 1;
        sim.cobble = 10;
        var step = PortalRunPlanner.plan(sim.snapshot(), BOTH).getLast();
        assertEquals(Action.GATHER_STONE, step.action(), "ten frame blocks to cast need about twenty blocks of mould and corners");
        assertEquals(10, step.count());
        sim.obsidian = 6;
        assertEquals(Action.CAST_PORTAL, PortalRunPlanner.plan(sim.snapshot(), BOTH).getLast().action(),
                "four left to cast need eight blocks; the classic route's stockpile of eighteen is not asked for");
    }

    @Test
    void castingKeepsNoSticksForADiamondPickaxe() {
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 24; sim.shields = 1; sim.tableNearby = true;
        sim.planks = 4; // A spare table's wood.
        assertEquals(2, PortalRunPlanner.sticksKept(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN), "the iron pickaxe's two");
        assertEquals(4, PortalRunPlanner.sticksKept(sim.snapshot(), WorldFacts.Method.MINE_OBSIDIAN), "and the diamond one's two more");
        // The spares follow the digging still ahead instead: casting's second bucket is three more iron to mine.
        int cast = PortalRunPlanner.sticksReserved(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN);
        int mine = PortalRunPlanner.sticksReserved(sim.snapshot(), WorldFacts.Method.MINE_OBSIDIAN);
        assertEquals(cast - 2, mine - 4 + 2, "one more stone pickaxe's pair of spares for the second bucket's iron");
        var bothNearTrees = new WorldFacts(java.util.Map.of(WorldFacts.Source.TREE, 10), BOTH.methods());
        sim.sticks = cast - 1;
        assertEquals(Action.GATHER_LOGS, PortalRunPlanner.plan(sim.snapshot(), bothNearTrees).getLast().action(), "short of the reserve: topped up");
        sim.sticks = cast;
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), bothNearTrees).getLast().action(), "packed: on to the iron");
    }

    @Test
    void aShieldIsMadeFromWoodKeptNearTheTreesAndTheFirstIron() {
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 24; sim.sticks = 24; sim.tableNearby = true; // Pickaxe sticks and the digging's spares.
        var trees = new WorldFacts(java.util.Map.of(WorldFacts.Source.TREE, 12), WorldFacts.ordinary().methods());
        var step = PortalRunPlanner.plan(sim.snapshot(), trees).getLast();
        assertEquals(Action.GATHER_LOGS, step.action(), "wood for a shield before going down for iron, while trees are in sight");
        assertTrue(step.why().contains("shield"));
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).getLast().action(),
                "no tree in sight: no detour for a shield");
        sim.logs = 2;
        assertEquals("Gather a log for a spare crafting table", PortalRunPlanner.plan(sim.snapshot(), trees).getLast().why(),
                "a shield's wood is not all: a table's worth more while the trees are in sight");
        sim.logs = 3;
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), trees).getLast().action());
        assertEquals(PortalRunPlanner.ironShortfall(sim.snapshot(), WorldFacts.Method.MINE_OBSIDIAN), 3 + 3 + 8 + 1 + 1,
                "with the wood in hand, one more iron for the shield");

        sim.rawIron = 1; sim.coal = 1; sim.furnaceNearby = true;
        assertEquals(Action.SMELT, PortalRunPlanner.plan(sim.snapshot(), trees).getLast().action(),
                "the first raw iron is smelted at once for the shield, before mining the rest");
        sim.rawIron = 0; sim.coal = 0; sim.iron = 1; sim.logs = 0; sim.planks = 8;
        var craft = PortalRunPlanner.plan(sim.snapshot(), trees).getLast();
        assertEquals(Action.CRAFT, craft.action());
        assertEquals("shield", craft.item(), "then made into a shield");

        sim.pick = 3; sim.iron = 13; sim.logs = 0; sim.planks = 0;
        assertNotEquals("shield", PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).getLast().item(),
                "without the kept wood there is no trip back up for a shield");
    }

    @Test
    void theIronPickaxeIsMadeAsSoonAsThereIsIronForIt() {
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 24; sim.sticks = 8; sim.shields = 1; sim.coal = 1;
        sim.tableNearby = true; sim.furnaceNearby = true;
        sim.rawIron = 2;
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot()).getLast().action(), "not enough for a pickaxe yet");
        sim.rawIron = 3;
        assertEquals(Action.SMELT, PortalRunPlanner.plan(sim.snapshot()).getLast().action(),
                "three raw iron are smelted at once, before the rest of the route's iron is mined");
        sim.apply(PortalRunPlanner.plan(sim.snapshot()).getLast());
        sim.apply(PortalRunPlanner.plan(sim.snapshot()).getLast());
        sim.apply(PortalRunPlanner.plan(sim.snapshot()).getFirst());
        var craft = PortalRunPlanner.plan(sim.snapshot()).getLast();
        assertEquals("iron_pickaxe", craft.item(), "then made into an iron pickaxe");
        sim.apply(craft);
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot()).getLast().action(), "and the mining goes on with it");
    }

    @Test
    void spareSticksAreToppedUpNearTreesButNeverFetchedFromBelow() {
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 24; sim.sticks = 2; sim.shields = 1; sim.tableNearby = true;
        var trees = new WorldFacts(java.util.Map.of(WorldFacts.Source.TREE, 10), BOTH.methods());
        var step = PortalRunPlanner.plan(sim.snapshot(), trees).getLast();
        assertEquals(Action.GATHER_LOGS, step.action(), "trees in sight: two sticks for the iron pickaxe and spares for the digging ahead");
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), BOTH).getLast().action(),
                "underground with no wood: no climb back up for spares");
        sim.planks = 4;
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), BOTH).getLast().action(), "a spare table's wood is kept, not made into sticks");
        sim.planks = 6;
        assertEquals("stick", PortalRunPlanner.plan(sim.snapshot(), BOTH).getLast().item(), "carried wood beyond it still becomes spare sticks");
        // Eighteen iron to mine at about fifty blocks each wears out seven stone pickaxes, and a pair over: sixteen spares.
        assertEquals(2 + 16, PortalRunPlanner.sticksReserved(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN));
        assertEquals(2 + 8, PortalRunPlanner.sticksReserved(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN, false),
                "no caves to dig in: the four spare pairs as before");
        sim.rawIron = 15;
        assertEquals(2 + 8, PortalRunPlanner.sticksReserved(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN), "most of the iron in hand: fewer spares");
        sim.rawIron = 0;
    }

    @Test
    void factsRejectNegativeDistances() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorldFacts(java.util.Map.of(WorldFacts.Source.LAVA, -1), java.util.Set.of()));
    }
}
