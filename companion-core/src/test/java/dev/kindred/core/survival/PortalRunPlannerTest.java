package dev.kindred.core.survival;

import dev.kindred.core.survival.PortalRunPlanner.Action;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: checks the player's plan from an empty inventory to a lit Nether portal: it follows the
 * whole route in a stand-in inventory, on ordinary worlds and sky islands, chooses between mining and casting
 * the obsidian frame, packs a spare pickaxe, sticks and food before going underground, does not let hunting
 * push the route aside, and waits for a teammate's portal instead of building its own.
 */
class PortalRunPlannerTest {

    /** Mutable stand-in for a player's inventory that applies each step's vanilla outcome. */
    private static final class Sim {
        int logs, planks, sticks, cobble, coal, rawIron, iron, diamonds, flint, gravel, obsidian, buckets, lava, water, fns,
                tables, furnaces, saplings, food = 5, pick, pickaxes, sword, chest, shields, furnaceInput, furnaceOutput, foodLevel = 20;
        boolean tableNearby, furnaceNearby, framed, lit;
        /** A sky island: no ground to dig, logs only from standing trees, which grow only from planted saplings. */
        boolean sky, generator;
        int treeLogs, leaves, planted, stoneLeft;
        /** Logs smelting into charcoal, and charcoal waiting in the furnace (counted as coal, as the goal counts it). */
        int charcoalIn, charcoalOut;

        /** Raw meat carried, and raw meat cooking in the furnace. */
        int rawMeat, meatCooking;

        PortalRunSnapshot snapshot() {
            return new PortalRunSnapshot(logs, planks, sticks, cobble, coal + charcoalOut, rawIron + furnaceInput, iron + furnaceOutput, diamonds,
                    flint, gravel, obsidian, buckets, lava, water, fns, tables, furnaces, saplings, food, pick, Math.max(pickaxes, pick >= 2 ? 1 : 0), sword, chest, shields,
                    tableNearby, furnaceNearby, furnaceInput + charcoalIn + meatCooking > 0, furnaceOutput + charcoalOut, foodLevel, framed, lit,
                    rawMeat, meatCooking > 0);
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
                    if ("log".equals(step.item())) { // A log in, a plank for its fuel.
                        assertTrue(logs >= 1 && planks >= 1, "a log to smelt and a plank to smelt it with");
                        logs--; planks--; charcoalIn++;
                        return;
                    }
                    int fuelItems = coal > 0 ? 1 : (rawIron * 2 + 2) / 3;
                    if (coal > 0) coal--; else planks -= Math.min(planks, fuelItems);
                    furnaceInput += rawIron;
                    rawIron = 0;
                }
                case AWAIT_SMELT -> { furnaceOutput += furnaceInput; furnaceInput = 0; charcoalOut += charcoalIn; charcoalIn = 0; }
                case COLLECT_SMELTED -> { iron += furnaceOutput; furnaceOutput = 0; coal += charcoalOut; charcoalOut = 0; }
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

    /**
     * With the portal lit, iron wanted for other work, such as rails, is still mined, smelted and collected.
     */
    @Test
    void ironForWorkOfItsOwnIsMinedAndSmeltedWhenTheRunHasNoMoreUseForIron() {
        // The portal lit and its iron long made: seventeen ingots more for rails and a minecart (E-45e), with a stone
        // pickaxe, a table by it and stone for a furnace, but no coal, wood or furnace.
        Sim sim = new Sim();
        sim.pick = 2;
        sim.lit = true;
        sim.tableNearby = true;
        sim.cobble = 8;
        assertTrue(PortalRunPlanner.ironFor(sim.snapshot(), WorldFacts.ordinary(), 0).isEmpty(), "Nothing to do for none");
        List<Action> seen = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            var step = PortalRunPlanner.ironFor(sim.snapshot(), WorldFacts.ordinary(), 17);
            if (step.isEmpty()) break;
            seen.add(step.get().action());
            sim.apply(step.get());
        }
        assertEquals(17, sim.iron, "All of it in hand: " + seen);
        assertEquals(17, seen.stream().filter(a -> a == Action.MINE_IRON).count(), "An ore a step, until it holds the number raw");
        assertTrue(seen.containsAll(List.of(Action.MINE_COAL, Action.CRAFT, Action.SMELT, Action.AWAIT_SMELT, Action.COLLECT_SMELTED)),
                "Fuel found, a furnace made and put down, the iron smelted and taken out: " + seen);
        assertTrue(seen.indexOf(Action.MINE_COAL) > seen.lastIndexOf(Action.MINE_IRON), "The ore first, then what to smelt it with");
        // A furnace still at work is waited for, not emptied an ingot at a time; emptied once it holds the number.
        var working = new Sim();
        working.pick = 2;
        working.furnaceNearby = true;
        working.furnaceInput = 16;
        working.furnaceOutput = 1;
        assertEquals(Action.AWAIT_SMELT, PortalRunPlanner.ironFor(working.snapshot(), WorldFacts.ordinary(), 17).orElseThrow().action());
        working.furnaceInput = 2;
        working.furnaceOutput = 17;
        assertEquals(Action.COLLECT_SMELTED, PortalRunPlanner.ironFor(working.snapshot(), WorldFacts.ordinary(), 17).orElseThrow().action(),
                "Seventeen out and two more still smelting: what it wants is there, taken out");
        // Iron in hand already counts; and with no pickaxe that mines iron ore there is nothing it can do from here.
        assertTrue(PortalRunPlanner.ironFor(sim.snapshot(), WorldFacts.ordinary(), 12).isEmpty(), "Held already");
        var bare = new Sim();
        assertTrue(PortalRunPlanner.ironFor(bare.snapshot(), WorldFacts.ordinary(), 6).isEmpty(), "No pickaxe for the ore");
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
                assertEquals(2, sim.pick, "no iron pickaxe either: the island's iron goes to the buckets and the flint and steel");
                return;
            }
            seen.add(step.action());
            sim.apply(step);
        }
        throw new AssertionError("Planner did not reach a lit portal on the sky island: " + seen.subList(Math.max(0, seen.size() - 20), seen.size()));
    }

    @Test
    void whereWoodIsGrownAndThereIsNoCoalASpareLogBecomesCharcoal() {
        // Sky-island run 13 smelted all its iron with wood and waited by saplings for most of the iron stage.
        Sim sim = skyIsland();
        sim.pick = 2; sim.pickaxes = 1; sim.sword = 2; sim.cobble = 20; sim.sticks = 8; sim.shields = 1; sim.tableNearby = true;
        sim.furnaceNearby = true; sim.rawIron = 7; sim.logs = 2; sim.planks = 3; sim.generator = true;
        sim.buckets = 0; sim.lava = 0; sim.water = 0; // The iron for both buckets and the flint and steel, mined: seven smelts ahead.
        var step = PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getLast();
        assertEquals(Action.SMELT, step.action());
        assertEquals("log", step.item(), "charcoal first: " + step);
        sim.apply(step);
        assertEquals(Action.AWAIT_SMELT, PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getLast().action());
        sim.apply(PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getLast());
        assertEquals(Action.COLLECT_SMELTED, PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getFirst().action());
        sim.apply(PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getFirst());
        var iron = PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts()).getLast();
        assertEquals("raw_iron", iron.item(), "then the iron, on the charcoal: " + iron);
        // Where coal is known, or with too little iron left to smelt, no charcoal.
        Sim near = new Sim();
        near.pick = 2; near.pickaxes = 2; near.sword = 2; near.cobble = 24; near.sticks = 20; near.shields = 1; near.tableNearby = true;
        near.furnaceNearby = true; near.rawIron = 2; near.logs = 2; near.planks = 3;
        assertNotEquals("log", PortalRunPlanner.plan(near.snapshot(), WorldFacts.ordinary()).getLast().item(), "an ordinary world digs coal");
    }

    /**
     * Before the work below, with trees in sight and no coal, wood to smelt the route's iron with is taken along; below,
     * with that wood carried, the raw iron is smelted with it and no coal is sought.
     */
    @Test
    void fuelForTheSmeltingBelowIsPackedWhileTreesAreInSight() {
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 24; sim.sticks = 40; sim.shields = 1; sim.tableNearby = true;
        sim.planks = 4; // A spare table's wood, packed already.
        var trees = new WorldFacts(java.util.Map.of(WorldFacts.Source.TREE, 8), WorldFacts.ordinary().methods());
        var step = PortalRunPlanner.plan(sim.snapshot(), trees).getLast();
        assertEquals(Action.GATHER_LOGS, step.action(), "wood for fuel first: " + step);
        assertTrue(step.why().contains("fuel"), step.why());
        // Fifteen iron to smelt is ten planks beyond the table's four: fourteen in all, three logs more.
        assertEquals(3, step.count());
        sim.logs = 3;
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), trees).getLast().action(), "then the iron");
        // Below, out of sight of trees, raw iron in hand and only logs carried: made into planks, which smelt four times
        // what a log burnt whole does, then the iron is smelted with them.
        sim.rawIron = 5; sim.furnaceNearby = true; sim.planks = 0; sim.logs = 4;
        var planks = PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).getLast();
        assertEquals(Action.CRAFT, planks.action(), "no coal sought with wood to burn: " + planks);
        assertEquals("planks", planks.item());
        sim.apply(planks);
        var smelt = PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).getLast();
        assertEquals(Action.SMELT, smelt.action(), "then smelted with them: " + smelt);
        // Coal enough for the rest is wood not taken.
        Sim coal = new Sim();
        coal.pick = 2; coal.pickaxes = 2; coal.sword = 2; coal.cobble = 24; coal.sticks = 40; coal.shields = 1; coal.tableNearby = true;
        coal.coal = 2; coal.logs = 1;
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(coal.snapshot(), trees).getLast().action(), "two coal smelt sixteen");
    }

    /**
     * Raw meat carried and its furnace at hand and idle: it is cooked, and waited for while it cooks; not while raw iron
     * waits for the furnace, nor with no fuel but the wood kept for a spare table.
     */
    @Test
    void rawMeatIsCookedInItsIdleFurnaceAndWaitedFor() {
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 24; sim.sticks = 40; sim.shields = 1; sim.tableNearby = true;
        sim.furnaceNearby = true; sim.coal = 1; sim.rawMeat = 6; sim.food = 6;
        var cook = PortalRunPlanner.plan(sim.snapshot()).stream().filter(step -> step.action() == Action.COOK).findFirst();
        assertTrue(cook.isPresent(), "cooked: " + PortalRunPlanner.plan(sim.snapshot()));
        assertEquals(6, cook.get().count());
        sim.rawMeat = 0; sim.meatCooking = 6;
        var steps = PortalRunPlanner.plan(sim.snapshot());
        assertTrue(steps.stream().anyMatch(step -> step.action() == Action.AWAIT_SMELT && step.why().contains("food")), "waited for: " + steps);
        sim.meatCooking = 0; sim.rawMeat = 6; sim.rawIron = 3;
        assertTrue(PortalRunPlanner.plan(sim.snapshot()).stream().noneMatch(step -> step.action() == Action.COOK), "not while iron waits");
        sim.rawIron = 0; sim.coal = 0; sim.logs = 1; // Four planks' worth, all kept for a spare table below.
        assertTrue(PortalRunPlanner.plan(sim.snapshot()).stream().noneMatch(step -> step.action() == Action.COOK), "not with the kept wood");
    }

    /**
     * Iron for the pickaxe in hand but no sticks or wood to make it with: the route's next step is logs. Below ground at
     * night the iron still wanted is the work meanwhile; with the iron all in hand, or no pickaxe for ore, there is none.
     */
    @Test
    void theRoutesIronIsTheWorkBelowGroundWhileTheNextStepWaitsForTrees() {
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 24; sim.shields = 1; sim.tableNearby = true; sim.iron = 3;
        var step = PortalRunPlanner.plan(sim.snapshot()).getLast();
        assertEquals(Action.GATHER_LOGS, step.action(), "sticks for the iron pickaxe: " + step);
        var meanwhile = PortalRunPlanner.withoutWood(sim.snapshot(), WorldFacts.ordinary()).orElseThrow();
        assertEquals(Action.MINE_IRON, meanwhile.action());
        assertEquals(12, meanwhile.count(), "fifteen for the route, three in hand");
        sim.iron = 15;
        assertTrue(PortalRunPlanner.withoutWood(sim.snapshot(), WorldFacts.ordinary()).isEmpty(), "all its iron in hand");
        Sim wooden = new Sim();
        wooden.pick = 1;
        assertTrue(PortalRunPlanner.withoutWood(wooden.snapshot(), WorldFacts.ordinary()).isEmpty(), "no pickaxe for iron ore");
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
        sim.coal = 2; // Its iron's fuel, so no wood is packed for it.
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
        sim.logs = 0; sim.planks = 4; sim.rawIron = 3; sim.furnaceNearby = true; sim.coal = 0;
        assertEquals(Action.MINE_COAL, PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).getLast().action());

        // Nothing to pack for where there is no ground below.
        Sim sky = skyIsland();
        sky.pick = 2; sky.pickaxes = 1; sky.sword = 2; sky.cobble = 24; sky.sticks = 12; sky.shields = 1; sky.tableNearby = true;
        assertNotEquals("stone_pickaxe", PortalRunPlanner.plan(sky.snapshot(), sky.skyFacts()).getLast().item());
    }

    /**
     * With its wooden pickaxe among the trees, the trip's wood is taken before the first cobblestone takes it below, and
     * below, out of sight of any tree, the iron stage asks for no more wood and hunts no coal. In the live runs on
     * fb02bc3 the packing came only after the first stone, and three of five players ran out of sticks and wood below.
     * Out of sight of trees there is nothing to pack; and a spare pickaxe it has neither sticks nor wood for is not
     * fetched from the surface.
     */
    @Test
    void theTripsWoodIsTakenAmongTheTreesBeforeTheFirstStone() {
        var trees = new WorldFacts(java.util.Map.of(WorldFacts.Source.TREE, 6), WorldFacts.ordinary().methods());
        Sim sim = new Sim();
        sim.pick = 1; sim.planks = 3; sim.tableNearby = true;
        var pack = PortalRunPlanner.plan(sim.snapshot(), trees).getLast();
        assertEquals(Action.GATHER_LOGS, pack.action(), "the trip's wood first: " + pack);
        assertTrue(pack.why().contains("trip below"), pack.why());
        assertEquals(Action.GATHER_STONE, PortalRunPlanner.plan(sim.snapshot()).getLast().action(), "no tree in sight: the stone");
        var farTree = new WorldFacts(java.util.Map.of(WorldFacts.Source.TREE, 40), WorldFacts.ordinary().methods());
        assertEquals(Action.GATHER_STONE, PortalRunPlanner.plan(sim.snapshot(), farTree).getLast().action(), "nor from forty blocks off");
        // Among the trees until the first stone; then below, with no tree in sight, on to the frame's diamonds.
        List<Action> below = new ArrayList<>();
        boolean down = false;
        for (int i = 0; i < 600; i++) {
            var step = PortalRunPlanner.plan(sim.snapshot(), down ? WorldFacts.ordinary() : trees).stream()
                    .filter(candidate -> candidate.action() != Action.HUNT).findFirst().orElseThrow();
            if (step.action() == Action.MINE_DIAMOND) break;
            if (step.action() == Action.GATHER_STONE) down = true;
            if (down) below.add(step.action());
            sim.apply(step);
        }
        assertEquals(3, sim.pick, "the iron pickaxe made: " + below);
        assertTrue(sim.chest >= 3 && sim.shields == 1 && sim.buckets >= 1, "its iron all made: " + below);
        assertTrue(!below.contains(Action.GATHER_LOGS), "no wood fetched from below: " + below);
        assertTrue(!below.contains(Action.MINE_COAL), "no coal hunted: " + below);
        // Below with no sticks or wood: on with the pickaxe it has, not up a staircase to the trees for a spare.
        Sim spare = new Sim();
        spare.pick = 2; spare.pickaxes = 1; spare.sword = 2; spare.cobble = 24; spare.shields = 1; spare.tableNearby = true;
        var next = PortalRunPlanner.plan(spare.snapshot()).getLast();
        assertEquals(Action.MINE_IRON, next.action(), "no spare fetched from the surface: " + next);
    }

    @Test
    void onTheCastRouteAnIronPickaxeLostOnceTheBucketsAreMadeIsNotMadeAgain() {
        // The live runs on 10a60d5: Kindred's iron pickaxe wore out by Y=-55 on its way to the lava; the iron for another,
        // and the coal to smelt it, took it back up sixty blocks.
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 30; sim.sticks = 8; sim.shields = 1; sim.chest = 3;
        sim.tableNearby = true; sim.iron = 2; sim.buckets = 1; sim.water = 1; sim.food = 20;
        assertEquals(WorldFacts.Method.CAST_OBSIDIAN, PortalRunPlanner.frameMethod(sim.snapshot(), BOTH));
        assertEquals(1, PortalRunPlanner.ironShortfall(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN, true), "the flint and steel's iron only");
        // On the mined route the frame's diamonds want it, buckets made or not.
        assertEquals(4, PortalRunPlanner.ironShortfall(sim.snapshot(), WorldFacts.Method.MINE_OBSIDIAN, true), "the pickaxe's three and the flint and steel's");
        var next = PortalRunPlanner.plan(sim.snapshot(), BOTH).getLast();
        assertEquals(Action.CAST_PORTAL, next.action(), "on to the cast with the stone pickaxe: " + next);
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < 40 && !sim.lit; i++) {
            var step = PortalRunPlanner.plan(sim.snapshot(), BOTH).getLast();
            steps.add(step.action() + (step.item().isEmpty() ? "" : " " + step.item()));
            sim.apply(step);
        }
        assertTrue(sim.lit, "lit: " + steps);
        assertTrue(steps.stream().noneMatch(s -> s.startsWith("MINE_IRON") || s.startsWith("MINE_COAL") || s.equals("CRAFT iron_pickaxe")),
                "no iron mined, no coal hunted, no iron pickaxe made: " + steps);
    }

    @Test
    void onTheCastRouteTheIronPickaxeIsMadeWithTheRestOfTheIronNotFirst() {
        // The long live run on a286cc8: Kindred made its iron pickaxe from its first three iron and wore it out before it
        // found the rest; the next three went to another, and so on: four in all, and never a bucket.
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 30; sim.sticks = 8; sim.shields = 1; sim.coal = 30;
        sim.tableNearby = true; sim.furnaceNearby = true; sim.food = 20;
        sim.iron = 3;
        assertEquals(WorldFacts.Method.CAST_OBSIDIAN, PortalRunPlanner.frameMethod(sim.snapshot(), BOTH));
        // Mined as Kindred mined it: each iron pickaxe worn out by the third iron found after it.
        int ironPickaxes = 0, mined = 0, wornAt = Integer.MAX_VALUE;
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < 300 && sim.buckets + sim.water < 2; i++) {
            var step = PortalRunPlanner.plan(sim.snapshot(), BOTH).stream().filter(candidate -> candidate.action() != Action.HUNT).findFirst().orElseThrow();
            steps.add(step.action() + (step.item().isEmpty() ? "" : " " + step.item()));
            if (step.action() == Action.CRAFT && "iron_pickaxe".equals(step.item())) { ironPickaxes++; wornAt = mined + 3; }
            sim.apply(step);
            if (step.action() == Action.MINE_IRON) mined++;
            if (sim.pick == 3 && mined >= wornAt) sim.pick = 2;
        }
        assertTrue(sim.buckets + sim.water >= 2, "both buckets made: " + steps);
        assertEquals(1, ironPickaxes, "one iron pickaxe, made with the rest of the iron: " + steps);
        assertEquals("MINE_IRON", steps.getFirst(), "its first three iron kept for the rest");
        // Where the frame is mined, it still comes first: the diamonds want it.
        Sim mine = new Sim();
        mine.pick = 2; mine.pickaxes = 2; mine.sword = 2; mine.cobble = 30; mine.sticks = 8; mine.shields = 1; mine.tableNearby = true;
        mine.iron = 3;
        assertEquals("iron_pickaxe", PortalRunPlanner.plan(mine.snapshot()).getLast().item(), "the iron pickaxe first on the mined route");
    }

    @Test
    void theStickReserveIsPackedFromATreeFurtherOffBeforeTheWorkBelow() {
        // Live run 68 went into a cave by spawn with few sticks and no wood, the nearest tree forty blocks off, and at
        // Y=19 had none left: it climbed fifty-five blocks for logs and wore out its pickaxes on the way.
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 24; sim.sticks = 2; sim.shields = 1; sim.tableNearby = true; sim.planks = 4;
        var farTree = new WorldFacts(java.util.Map.of(WorldFacts.Source.TREE, 40), WorldFacts.ordinary().methods());
        assertEquals("Gather a log for spare sticks", PortalRunPlanner.plan(sim.snapshot(), farTree).getLast().why(),
                "forty blocks to a tree beats a climb back up from the iron");
        var tooFar = new WorldFacts(java.util.Map.of(WorldFacts.Source.TREE, 100), WorldFacts.ordinary().methods());
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), tooFar).getLast().action(), "but not a long trek for one");
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).getLast().action(),
                "below ground, with no tree known, the iron comes first as before");
        sim.sticks = PortalRunPlanner.sticksReserved(sim.snapshot(), WorldFacts.Method.MINE_OBSIDIAN);
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), farTree).getLast().action(), "packed: on to the iron");
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

    /** Seeing diamond ore switches the plan from casting the portal frame to mining it. */
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
        sim.coal = 3; // Its iron's fuel, so no wood is packed for it.
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
        sim.coal = 2; // Its iron's fuel, so no wood is packed for it.
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
        assertEquals(8, PortalRunPlanner.sticksReserved(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN, false),
                "no caves to dig in, and so no iron pickaxe to make: the four spare pairs as before");
        sim.rawIron = 15;
        assertEquals(2 + 8, PortalRunPlanner.sticksReserved(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN), "most of the iron in hand: fewer spares");
        sim.rawIron = 0;
    }

    @Test
    void factsRejectNegativeDistances() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorldFacts(java.util.Map.of(WorldFacts.Source.LAVA, -1), java.util.Set.of()));
    }

    /** Iron stage below ground, stocked as usual (six food), coal for its iron, a table at hand, trees in sight. */
    private static Sim ironWork() {
        Sim sim = new Sim();
        sim.pick = 2; sim.pickaxes = 2; sim.sword = 2; sim.cobble = 24; sim.sticks = 20; sim.shields = 1; sim.tableNearby = true;
        sim.food = 6; sim.planks = 4; sim.coal = 2;
        return sim;
    }

    private static final WorldFacts TREES = new WorldFacts(java.util.Map.of(WorldFacts.Source.TREE, 10), WorldFacts.ordinary().methods());

    @Test
    void withATeammateBuildingThePortalAGathererGetsItsIronThenFoodAndWoodForTheRunAndWaitsForTheTeamsPortal() {
        var team = new PortalRunPlanner.Team(true);
        // Its own iron not in yet: the route as ever, no waiting on anyone.
        Sim early = new Sim();
        early.pick = 2;
        early.tableNearby = true;
        var earlySteps = PortalRunPlanner.plan(early.snapshot(), WorldFacts.ordinary(), PlanPreferences.NONE, team);
        assertTrue(earlySteps.stream().noneMatch(st -> st.action() == Action.AWAIT_TEAM_PORTAL), "its own iron first: " + earlySteps);
        // Past its iron (a diamond pickaxe in hand): food and wood for the run, then the team's portal; no portal of its own.
        Sim sim = new Sim();
        sim.pick = 4;
        sim.chest = 3;
        var steps = PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary(), PlanPreferences.NONE, team);
        assertEquals(List.of(Action.HUNT, Action.SEEK_FOOD, Action.CUT_TREES, Action.AWAIT_TEAM_PORTAL), steps.stream().map(PortalRunPlanner.Step::action).toList());
        assertTrue(steps.stream().allMatch(st -> st.stage() == PortalRunPlanner.Stage.PORTAL), steps.toString());
        assertEquals(PortalRunPlanner.Stage.PORTAL, PortalRunPlanner.stage(sim.snapshot(), WorldFacts.ordinary(), team));
        sim.food = PortalRunPlanner.FOOD_STOCK + PortalRunPlanner.RUN_FOOD;
        sim.logs = PortalRunPlanner.RUN_WOOD / 4;
        // With the wood for one in hand, its shield is its own gear still to make; no flint and steel is: that is the portal's.
        var shieldFirst = PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary(), PlanPreferences.NONE, team);
        assertTrue(shieldFirst.stream().noneMatch(st -> st.action() == Action.AWAIT_TEAM_PORTAL), "its shield first: " + shieldFirst);
        sim.shields = 1;
        assertEquals(List.of(Action.AWAIT_TEAM_PORTAL), PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary(), PlanPreferences.NONE, team)
                .stream().map(PortalRunPlanner.Step::action).toList(), "stocked: it waits");
        // Alone, the same player makes its own portal.
        var alone = PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary(), PlanPreferences.NONE);
        assertTrue(alone.stream().noneMatch(st -> st.action() == Action.AWAIT_TEAM_PORTAL), alone.toString());
        assertNotEquals(PortalRunPlanner.Stage.PORTAL, PortalRunPlanner.stage(sim.snapshot(), WorldFacts.ordinary()));
        // Once the team's portal is lit, it is done like any other.
        sim.lit = true;
        assertEquals(Action.DONE, PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary(), PlanPreferences.NONE, team).getFirst().action());
        assertEquals(PortalRunPlanner.Stage.DONE, PortalRunPlanner.stage(sim.snapshot(), WorldFacts.ordinary(), team));
    }

    private static PlanPreferences focus(PlanPreferences.Focus focus) { return new PlanPreferences(java.util.Set.of(), focus); }

    private static PlanPreferences habit(PlanPreferences.Habit habit) { return new PlanPreferences(java.util.Set.of(habit), PlanPreferences.Focus.NONE); }

    @Test
    void foodFirstPutsTheHuntAndTheSearchForAnimalsAheadOfTheRoute() {
        Sim sim = ironWork();
        assertTrue(PortalRunPlanner.plan(sim.snapshot(), TREES).stream().noneMatch(step -> step.action() == Action.HUNT),
                "stocked as usual, the route goes on to iron");
        var steps = PortalRunPlanner.plan(sim.snapshot(), TREES, focus(PlanPreferences.Focus.FOOD));
        assertEquals(Action.HUNT, steps.get(0).action());
        assertEquals(Action.SEEK_FOOD, steps.get(1).action());
        assertEquals(PortalRunPlanner.FOOD_STOCK + 3 + PlanPreferences.FOCUS_FOOD - 6, steps.get(0).count(), "three more than the trip's stock");
        assertEquals(Action.MINE_IRON, steps.getLast().action(), "the route's own step stays last");
    }

    @Test
    void foodFirstEndsOnceWellStocked() {
        Sim sim = ironWork();
        sim.food = PortalRunPlanner.FOOD_STOCK + 3 + PlanPreferences.FOCUS_FOOD;
        assertTrue(PortalRunPlanner.plan(sim.snapshot(), TREES, focus(PlanPreferences.Focus.FOOD)).stream()
                .noneMatch(step -> step.action() == Action.HUNT || step.action() == Action.SEEK_FOOD));
    }

    @Test
    void woodFirstGathersLogsUntilThereIsWoodToSpare() {
        Sim sim = ironWork();
        var steps = PortalRunPlanner.plan(sim.snapshot(), TREES, focus(PlanPreferences.Focus.WOOD));
        assertEquals(Action.CUT_TREES, steps.getFirst().action());
        assertTrue(PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary(), focus(PlanPreferences.Focus.WOOD)).stream()
                .noneMatch(step -> step.action() == Action.CUT_TREES), "no tree known: nothing to cut first, and no search ahead of the route");
        assertTrue(steps.getFirst().why().contains("Wood first"));
        sim.logs = 5; // Twenty planks more: past the four kept for a spare table and the sixteen the focus wants.
        assertTrue(PortalRunPlanner.plan(sim.snapshot(), TREES, focus(PlanPreferences.Focus.WOOD)).stream()
                .noneMatch(step -> step.why().contains("Wood first")));
    }

    /**
     * On a sky island with no tree grown yet, a wood-first player has nothing to cut and goes on with the
     * route.
     */
    @Test
    void woodFirstLeavesSaplingWaitsToTheRouteOnASkyIsland() {
        Sim sim = skyIsland();
        sim.treeLogs = 0;
        sim.planted = 1;
        assertTrue(PortalRunPlanner.plan(sim.snapshot(), sim.skyFacts(), focus(PlanPreferences.Focus.WOOD)).stream()
                .noneMatch(step -> step.why().contains("Wood first")), "no tree standing: nothing to cut first");
    }

    @Test
    void aPlayerThatLearnedToCarryMoreFoodStocksThreeMore() {
        Sim sim = ironWork();
        var steps = PortalRunPlanner.plan(sim.snapshot(), TREES, habit(PlanPreferences.Habit.MORE_FOOD));
        assertEquals(Action.HUNT, steps.getFirst().action());
        assertEquals(PlanPreferences.EXTRA_FOOD, steps.getFirst().count());
    }

    @Test
    void aPlayerThatLearnedOneSpareIsNotEnoughPacksTwo() {
        Sim sim = ironWork();
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), TREES).getLast().action(), "one spare packed already");
        var step = PortalRunPlanner.plan(sim.snapshot(), TREES, habit(PlanPreferences.Habit.SPARE_PICKAXE)).getLast();
        assertEquals("stone_pickaxe", step.item(), "a second spare");
        sim.apply(step);
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), TREES, habit(PlanPreferences.Habit.SPARE_PICKAXE)).getLast().action());
    }
}
