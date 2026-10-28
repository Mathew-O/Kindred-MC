package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BuildNeedsTest {
    private static final String LOG = "minecraft:oak_log", PLANKS = "minecraft:oak_planks", STAIRS = "minecraft:oak_stairs",
            FENCE = "minecraft:oak_fence", STICK = "minecraft:stick", COBBLE = "minecraft:cobblestone", TORCH = "minecraft:torch";

    @Test
    void aShortfallIsMetFromStorageThenFromWhatItGathersAndCraftsInputsFirst() {
        var plan = BuildNeeds.plan(Map.of(STAIRS, 8, PLANKS, 10), Map.of(), Map.of(), "oak", true);
        assertEquals(Map.of(LOG, 6), plan.gather(), "six logs: twelve planks for two crafts of stairs, ten for the rest, two over");
        assertEquals(8, plan.crafts().size());
        assertEquals(PLANKS, plan.crafts().getFirst().item(), "the planks before the stairs they go into");
        assertTrue(plan.crafts().indexOf(BuildNeeds.recipe(STAIRS, "oak").orElseThrow()) > plan.crafts().indexOf(BuildNeeds.recipe(PLANKS, "oak").orElseThrow()));

        // Fences: four planks and two sticks a craft, three to a craft; the sticks' leftovers count.
        var fences = BuildNeeds.plan(Map.of(FENCE, 4), Map.of(), Map.of(), "oak", true);
        assertEquals(Map.of(LOG, 3), fences.gather(), "two crafts: eight planks and four sticks (two planks), ten planks in all");

        // What storage holds is taken first, logs for the planks among it.
        var stored = BuildNeeds.plan(Map.of(PLANKS, 8, TORCH, 1), Map.of(), Map.of(LOG, 5, TORCH, 3), "oak", true);
        assertEquals(Map.of(LOG, 2, TORCH, 1), stored.take());
        assertTrue(stored.gather().isEmpty());

        // Cobblestone wants a pickaxe, made first; glass is not to be had yet.
        var stone = BuildNeeds.plan(Map.of(COBBLE, 20, "minecraft:glass", 4), Map.of(), Map.of(), "oak", false);
        assertEquals(20, stone.gather().get(COBBLE));
        assertTrue(stone.crafts().stream().anyMatch(r -> r.item().equals("minecraft:wooden_pickaxe")));
        assertEquals(Set.of("minecraft:glass"), stone.unobtainable());
    }

    @Test
    void aHallsBedsAreMadeFromWoolItGathersAndPlanks() {
        // Three beds, for a hall of three cells: nine white wool from sheep, nine planks of the hall's wood.
        var plan = BuildNeeds.plan(Map.of("minecraft:white_bed", 3), Map.of(), Map.of(), "oak", true);
        assertEquals(Map.of("minecraft:white_wool", 9, LOG, 3), plan.gather(), "nine wool, and three logs for the nine planks (three over)");
        assertEquals(3, plan.crafts().stream().filter(r -> r.item().equals("minecraft:white_bed")).count());
        assertTrue(plan.unobtainable().isEmpty());
        // Planks in hand to spare: only the wool to get; a red bed wants red wool.
        var held = BuildNeeds.plan(Map.of("minecraft:white_bed", 3), Map.of(PLANKS, 9), Map.of(), "oak", true);
        assertEquals(Map.of("minecraft:white_wool", 9), held.gather());
        assertEquals(Map.of("minecraft:red_wool", 3), BuildNeeds.plan(Map.of("minecraft:red_bed", 1), Map.of(PLANKS, 3), Map.of(), "oak", true).gather());
        assertTrue(BuildNeeds.gathered("minecraft:light_gray_wool"));
        assertFalse(BuildNeeds.gathered("minecraft:white_carpet"));
    }

    @Test
    void itBuildsWhatItCanThenFetchesTheWholeShortfallInOneTripThenFinishes() {
        var needs = new BuildNeeds();
        // The floor's planks in hand, the stairs short: it builds the floor first.
        var needed = Map.of(PLANKS, 25, STAIRS, 8);
        assertInstanceOf(BuildNeeds.Step.Build.class, needs.next(needed, Map.of(PLANKS, 25), true, Map.of(), false, "oak", true));
        // The floor laid, nothing more it can place: one trip for all of the stairs' wood, not a log at a time.
        needed = Map.of(STAIRS, 8);
        var gather = assertInstanceOf(BuildNeeds.Step.Gather.class, needs.next(needed, Map.of(), false, Map.of(), false, "oak", true));
        assertEquals(LOG, gather.item());
        assertEquals(3, gather.count());
        assertTrue(needs.onTrip());
        // Partway through the trip, something it holds could go in, yet it finishes the trip first.
        var more = assertInstanceOf(BuildNeeds.Step.Gather.class, needs.next(needed, Map.of(LOG, 1), true, Map.of(), false, "oak", true));
        assertEquals(2, more.count());
        // All the logs in hand: the planks, then the stairs.
        var planks = assertInstanceOf(BuildNeeds.Step.Craft.class, needs.next(needed, Map.of(LOG, 3), true, Map.of(), false, "oak", true));
        assertEquals(PLANKS, planks.recipe().item());
        var stairs = assertInstanceOf(BuildNeeds.Step.Craft.class, needs.next(needed, Map.of(PLANKS, 12), true, Map.of(), false, "oak", true));
        assertEquals(STAIRS, stairs.recipe().item());
        // Everything in hand: back to building, the trip over.
        assertInstanceOf(BuildNeeds.Step.Build.class, needs.next(needed, Map.of(STAIRS, 8), true, Map.of(), false, "oak", true));
        assertFalse(needs.onTrip());
    }

    @Test
    void whatIsEasyToGetItGetsAtOnce() {
        // Its own storage close by holds the torch: taken now, though it could go on building.
        var needs = new BuildNeeds();
        var take = assertInstanceOf(BuildNeeds.Step.Take.class,
                needs.next(Map.of(PLANKS, 10, TORCH, 1), Map.of(PLANKS, 10), true, Map.of(TORCH, 2), true, "oak", true));
        assertEquals(Map.of(TORCH, 1), take.items());
        // Far off, the same storage waits for the trip.
        assertInstanceOf(BuildNeeds.Step.Build.class,
                new BuildNeeds().next(Map.of(PLANKS, 10, TORCH, 1), Map.of(PLANKS, 10), true, Map.of(TORCH, 2), false, "oak", true));
        // What it can make from what it carries, it makes before it builds on: no walk at all.
        var craft = assertInstanceOf(BuildNeeds.Step.Craft.class,
                new BuildNeeds().next(Map.of(PLANKS, 8, COBBLE, 4), Map.of(LOG, 2, COBBLE, 4), true, Map.of(), false, "oak", true));
        assertEquals(PLANKS, craft.recipe().item());
        // Planks the build places are not spare to make stairs from.
        var plan = new BuildNeeds().next(Map.of(PLANKS, 25, STAIRS, 4), Map.of(PLANKS, 25), false, Map.of(), false, "oak", true);
        assertEquals(LOG, assertInstanceOf(BuildNeeds.Step.Gather.class, plan).item());
    }

    @Test
    void whatNoWayGetsItSaysSo() {
        var step = new BuildNeeds().next(Map.of("minecraft:glass", 4), Map.of(), false, Map.of(), false, "oak", true);
        var shortOf = assertInstanceOf(BuildNeeds.Step.Short.class, step);
        assertEquals(Set.of("minecraft:glass"), shortOf.items());
        assertTrue(BuildNeeds.recipe("minecraft:spruce_fence_gate", "oak").orElseThrow().takes().containsKey("minecraft:spruce_planks"));
        assertEquals("minecraft:birch_planks", BuildNeeds.recipe(STICK, "birch").orElseThrow().takes().keySet().iterator().next());
    }

    @Test
    void aTripEndsOnceEverythingIsInHandHoweverTheLastOfItCame() {
        var needs = new BuildNeeds();
        // Stuck for cobblestone it has no stone for: it sets out.
        var out = needs.next(Map.of(COBBLE, 10), Map.of(), false, Map.of(), false, "oak", true);
        assertInstanceOf(BuildNeeds.Step.Gather.class, out);
        assertTrue(needs.onTrip());
        // The last of it came in by a way the queue was not asked about (a craft, a pick-up): the trip is over.
        needs.allInHand();
        assertFalse(needs.onTrip());
    }
}
