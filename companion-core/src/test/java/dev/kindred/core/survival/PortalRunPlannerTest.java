package dev.kindred.core.survival;

import dev.kindred.core.survival.PortalRunPlanner.Action;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRunPlannerTest {

    /** Mutable stand-in for a player's inventory that applies each step's vanilla outcome. */
    private static final class Sim {
        int logs, planks, sticks, cobble, coal, rawIron, iron, diamonds, flint, gravel, obsidian, buckets, water, fns,
                tables, furnaces, food = 5, pick, sword, chest, furnaceInput, furnaceOutput, foodLevel = 20;
        boolean tableNearby, furnaceNearby, framed, lit;

        PortalRunSnapshot snapshot() {
            return new PortalRunSnapshot(logs, planks, sticks, cobble, coal, rawIron + furnaceInput, iron + furnaceOutput, diamonds,
                    flint, gravel, obsidian, buckets, water, fns, tables, furnaces, food, pick, sword, chest,
                    tableNearby, furnaceNearby, furnaceInput > 0, furnaceOutput, foodLevel, framed, lit);
        }

        void apply(PortalRunPlanner.Step step) {
            switch (step.action()) {
                case GATHER_LOGS -> logs++;
                case GATHER_STONE -> { assertTrue(pick >= 1, "stone needs a pickaxe"); cobble++; }
                case MINE_COAL -> coal++;
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
                case FILL_WATER -> { buckets--; water++; }
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
                case "stone_pickaxe" -> { need(0, 2, 3); pick = 2; }
                case "stone_sword" -> { need(0, 1, 2); sword = 2; }
                case "furnace" -> { need(0, 0, 8); furnaces++; }
                case "iron_pickaxe" -> { needIron(3); sticks -= 2; pick = 3; }
                case "bucket" -> { needIron(3); buckets++; }
                case "iron_chestplate" -> { needIron(8); chest = 3; }
                case "diamond_pickaxe" -> { assertTrue(diamonds >= 3 && sticks >= 2 && tableNearby); diamonds -= 3; sticks -= 2; pick = 4; }
                case "flint_and_steel" -> { assertTrue(flint > 0 && iron > 0); flint--; iron--; fns++; }
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

    /** Both frame methods available, nothing seen yet. */
    private static final WorldFacts BOTH = new WorldFacts(java.util.Map.of(),
            java.util.Set.of(WorldFacts.Method.MINE_OBSIDIAN, WorldFacts.Method.CAST_OBSIDIAN));

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
        sim.pick = 3; sim.sword = 2; sim.chest = 3; sim.buckets = 1; sim.fns = 1; sim.cobble = 24; sim.sticks = 4;
        sim.tableNearby = true; sim.furnaceNearby = true;
        return sim;
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
        sim.pick = 2; sim.sword = 2; sim.cobble = 24; sim.sticks = 2; sim.tableNearby = true;
        assertEquals(Action.GATHER_LOGS, PortalRunPlanner.plan(sim.snapshot(), WorldFacts.ordinary()).getLast().action(),
                "the classic route keeps two more sticks for the diamond pickaxe");
        assertEquals(Action.MINE_IRON, PortalRunPlanner.plan(sim.snapshot(), BOTH).getLast().action(),
                "casting needs only the iron pickaxe's two, so it goes on to iron");
        assertEquals(2, PortalRunPlanner.sticksKept(sim.snapshot(), WorldFacts.Method.CAST_OBSIDIAN));
        assertEquals(4, PortalRunPlanner.sticksKept(sim.snapshot(), WorldFacts.Method.MINE_OBSIDIAN));
    }

    @Test
    void factsRejectNegativeDistances() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorldFacts(java.util.Map.of(WorldFacts.Source.LAVA, -1), java.util.Set.of()));
    }
}
