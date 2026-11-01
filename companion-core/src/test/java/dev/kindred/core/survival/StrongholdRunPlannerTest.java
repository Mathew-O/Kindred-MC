package dev.kindred.core.survival;

import dev.kindred.core.guide.Need;
import dev.kindred.core.guide.Objective;
import dev.kindred.core.guide.TaskQueue;
import dev.kindred.core.survival.StrongholdRunPlanner.Action;
import dev.kindred.core.survival.StrongholdRunPlanner.Provisions;
import dev.kindred.core.survival.StrongholdRunPlanner.Stage;
import dev.kindred.core.survival.StrongholdRunSnapshot.Dimension;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the player's plan from a lit portal to the stronghold's portal room: stock up first,
 * get blaze rods at a fortress and ender pearls, make and throw eyes back home, follow them and dig down, and
 * pick up what the End fight needs along the way without stopping the trip.
 */
class StrongholdRunPlannerTest {
    /** A player just through lighting its portal: a stone sword and pickaxe, little food, few blocks. */
    private static Builder justLit() {
        return new Builder();
    }

    private static final class Builder {
        Dimension dimension = Dimension.OVERWORLD;
        boolean portalKnown = true, fortressSeen, blazeSeen, endermanSeen, stalled, estimate, near, strongholdSeen, roomSeen;
        int food = 3, foodLevel = 20, sword = 2, pickaxe = 2, pickaxes = 1, armor = 1, shields = 0, blocks = 10, iron = 0;
        int rods, powder, pearls, eyes, throwsRecorded, filled, boats, planks, logs;

        Builder in(Dimension d) { dimension = d; return this; }
        Builder food(int n, int level) { food = n; foodLevel = level; return this; }
        Builder iron(int n) { iron = n; return this; }
        Builder rods(int n) { rods = n; return this; }
        Builder powder(int n) { powder = n; return this; }
        Builder pearls(int n) { pearls = n; return this; }
        Builder eyes(int n) { eyes = n; return this; }
        Builder throwsRecorded(int n) { throwsRecorded = n; return this; }
        Builder fortress() { fortressSeen = true; return this; }
        Builder blaze() { blazeSeen = true; return this; }
        Builder enderman() { endermanSeen = true; return this; }
        Builder stalled() { stalled = true; return this; }
        Builder estimate() { estimate = true; return this; }
        Builder near() { near = true; return this; }
        Builder stronghold() { strongholdSeen = true; return this; }
        Builder room() { roomSeen = true; return this; }
        Builder ready() { food = 12; blocks = 48; pickaxes = 2; return this; }
        Builder wood(int planks, int logs) { this.planks = planks; this.logs = logs; return this; }
        Builder boat() { boats = 1; return this; }

        StrongholdRunSnapshot build() {
            return new StrongholdRunSnapshot(dimension, portalKnown, food, foodLevel, sword, pickaxe, pickaxes, armor, shields, blocks, iron,
                    rods, powder, pearls, eyes, fortressSeen, blazeSeen, endermanSeen, stalled, throwsRecorded, estimate, near,
                    strongholdSeen, roomSeen, filled, boats, planks, logs);
        }
    }

    private static List<Action> actions(StrongholdRunSnapshot s) {
        return StrongholdRunPlanner.plan(s).stream().map(StrongholdRunPlanner.Step::action).toList();
    }

    @Test
    void itStocksUpBeforeGoingThroughAndPutsIronIntoWhatKeepsItAlive() {
        var s = justLit().iron(9).build();
        assertEquals(Stage.PREPARE, StrongholdRunPlanner.stage(s));
        var crafts = StrongholdRunPlanner.plan(s).stream().filter(step -> step.action() == Action.CRAFT)
                .map(StrongholdRunPlanner.Step::item).toList();
        assertEquals(List.of("iron_sword", "shield", "iron_helmet", "stone_pickaxe"), crafts,
                "nine iron: a sword, a shield, a helmet, in that order; and a spare pickaxe");
        // Food and blocks are the Nether trip's needs in the queue, gathered at its gate.
        var followUps = StrongholdRunPlanner.plan(s).stream().filter(step -> step.action() == Action.FOLLOW_UP).toList();
        assertEquals(List.of("BLOCKS", "FOOD"), followUps.stream().map(StrongholdRunPlanner.Step::item).toList());
        assertEquals("Gather 9 food for the Nether trip", followUps.getLast().why());
        var ready = justLit().ready().build();
        assertEquals(Stage.NETHER, StrongholdRunPlanner.stage(ready));
        assertEquals(List.of(Action.ENTER_PORTAL), actions(ready));
    }

    @Test
    void itMakesABoatFromWoodItCarriesButDoesNotWaitForOne() {
        var planks = justLit().wood(6, 0).build();
        assertTrue(StrongholdRunPlanner.plan(planks).stream().anyMatch(step -> step.action() == Action.CRAFT && step.item().equals("boat")),
                "five planks or more: a boat");
        var logs = justLit().wood(1, 1).build();
        assertTrue(StrongholdRunPlanner.plan(logs).stream().anyMatch(step -> step.action() == Action.CRAFT && step.item().equals("planks")),
                "a log and a plank make five planks: planks first");
        var bare = justLit().wood(2, 0).build();
        assertTrue(StrongholdRunPlanner.plan(bare).stream().noneMatch(step -> step.item().equals("boat") || step.item().equals("planks")),
                "too little wood: no boat");
        var has = justLit().wood(10, 4).boat().build();
        assertTrue(StrongholdRunPlanner.plan(has).stream().noneMatch(step -> step.item().equals("boat")), "one boat is enough");
        // Ready otherwise, with wood and no boat: through the portal all the same.
        assertEquals(Stage.NETHER, StrongholdRunPlanner.stage(justLit().ready().wood(8, 0).build()));
    }

    @Test
    void inTheNetherItFindsAFortressTakesRodsThenPearlsThenGoesBack() {
        var arrived = justLit().ready().in(Dimension.NETHER).build();
        assertEquals(Stage.FORTRESS, StrongholdRunPlanner.stage(arrived));
        assertEquals(List.of(Action.FIND_FORTRESS), actions(arrived));
        var atFortress = justLit().ready().in(Dimension.NETHER).fortress().blaze().rods(2).build();
        assertEquals(Stage.BLAZE_RODS, StrongholdRunPlanner.stage(atFortress));
        assertEquals(List.of(Action.FIGHT_BLAZE, Action.SEEK_BLAZE), actions(atFortress));
        var rods = justLit().ready().in(Dimension.NETHER).fortress().rods(6).pearls(3).build();
        assertEquals(Stage.PEARLS, StrongholdRunPlanner.stage(rods));
        assertEquals(List.of(Action.SEEK_ENDERMAN), actions(rods));
        var done = justLit().ready().in(Dimension.NETHER).fortress().rods(6).pearls(12).build();
        assertEquals(Stage.RETURN, StrongholdRunPlanner.stage(done));
        assertEquals(List.of(Action.ENTER_PORTAL), actions(done));
        // Powder and eyes already made count toward the rods.
        assertEquals(Stage.PEARLS, StrongholdRunPlanner.stage(justLit().in(Dimension.NETHER).rods(3).powder(4).eyes(2).pearls(1).build()));
    }

    @Test
    void anEndermanAnywhereIsWorthTheFightWhilePearlsAreShort() {
        var s = justLit().ready().in(Dimension.NETHER).enderman().blaze().build();
        assertEquals(List.of(Action.FIGHT_ENDERMAN, Action.FIGHT_BLAZE, Action.FIND_FORTRESS), actions(s));
        var overworld = justLit().rods(6).pearls(8).enderman().build();
        assertEquals(Action.FIGHT_ENDERMAN, actions(overworld).getFirst(), "on the way, too");
        assertFalse(actions(justLit().rods(6).pearls(12).enderman().build()).contains(Action.FIGHT_ENDERMAN), "not with pearls enough");
    }

    @Test
    void aStalledPearlHuntMovesOnWithEnoughForTheSearch() {
        var four = justLit().in(Dimension.NETHER).rods(6).pearls(4).stalled().build();
        assertEquals(Stage.PEARLS, StrongholdRunPlanner.stage(four), "four eyes are not enough to find it");
        var six = justLit().in(Dimension.NETHER).rods(6).pearls(6).stalled().build();
        assertEquals(Stage.RETURN, StrongholdRunPlanner.stage(six));
    }

    @Test
    void backInTheOverworldItMakesEyesThrowsFollowsDigsAndFindsTheRoom() {
        var back = justLit().rods(6).pearls(12).build();
        assertEquals(Stage.EYES, StrongholdRunPlanner.stage(back));
        var steps = StrongholdRunPlanner.plan(back);
        assertEquals("blaze_powder", steps.get(0).item());
        assertEquals("ender_eye", steps.get(1).item());
        assertEquals(12, steps.get(1).count());
        var eyes = justLit().eyes(12).build();
        assertEquals(Stage.LOCATE, StrongholdRunPlanner.stage(eyes));
        assertEquals(List.of(Action.THROW_EYE), actions(eyes));
        assertEquals(Stage.TRAVEL, StrongholdRunPlanner.stage(justLit().eyes(10).throwsRecorded(2).estimate().build()));
        assertEquals(Stage.DESCEND, StrongholdRunPlanner.stage(justLit().eyes(10).throwsRecorded(2).estimate().near().build()));
        assertEquals(Stage.PORTAL_ROOM, StrongholdRunPlanner.stage(justLit().eyes(10).estimate().near().stronghold().build()));
        var found = justLit().eyes(10).estimate().near().stronghold().room().build();
        assertEquals(Stage.DONE, StrongholdRunPlanner.stage(found));
        assertEquals(List.of(Action.DONE), actions(found));
    }

    @Test
    void anEyeLostInFlightNeverSendsItBackWhileItCanStillFindThePlace() {
        // Six made, one thrown and broken: five left, below the six wanted before the search, yet the search goes on.
        assertEquals(Stage.LOCATE, StrongholdRunPlanner.stage(justLit().eyes(5).throwsRecorded(1).build()));
        // With the crossing known, none are needed to get there.
        assertEquals(Stage.TRAVEL, StrongholdRunPlanner.stage(justLit().eyes(0).throwsRecorded(2).estimate().build()));
        // Out of eyes before a second flight: back for more.
        assertEquals(Stage.PREPARE, StrongholdRunPlanner.stage(justLit().eyes(0).throwsRecorded(1).build()));
        assertEquals(Stage.NETHER, StrongholdRunPlanner.stage(justLit().ready().eyes(0).throwsRecorded(1).build()));
    }

    @Test
    void outOfFoodComesFirstAboveGround() {
        assertEquals(Action.HUNT, actions(justLit().eyes(12).food(0, 10).build()).getFirst());
        assertEquals(List.of(Action.THROW_EYE), actions(justLit().eyes(12).food(0, 20).build()), "a full stomach can wait");
        assertFalse(actions(justLit().ready().in(Dimension.NETHER).food(0, 10).build()).contains(Action.HUNT),
                "nothing to hunt in the Nether");
    }

    private static Provisions endFight(Map<Need, Integer> held, Set<Need> atHand, boolean due) {
        var chunks = TaskQueue.EMPTY.chunks(Set.of(Objective.END_FIGHT), held);
        return new Provisions(chunks, due && !chunks.isEmpty() ? Map.of(Objective.END_FIGHT, chunks) : Map.of(), atHand, held);
    }

    @Test
    void theEndFightsNeedsAreTakenAsTheChanceComesWithoutStoppingTheRoute() {
        var travelling = justLit().eyes(10).throwsRecorded(2).estimate().build();
        // Nothing at hand: the route goes on.
        assertEquals(List.of(Action.TRAVEL_TO_STRONGHOLD), StrongholdRunPlanner.plan(travelling, endFight(Map.of(), Set.of(), false))
                .stream().map(StrongholdRunPlanner.Step::action).toList());
        // A chicken in sight while feathers are wanted: a short detour first, then the route.
        var steps = StrongholdRunPlanner.plan(travelling, endFight(Map.of(), Set.of(Need.FEATHERS), false));
        assertEquals(Action.FOLLOW_UP, steps.getFirst().action());
        assertEquals("FEATHERS", steps.getFirst().item());
        assertEquals("Gather 8 feathers for the End fight", steps.getFirst().why());
        assertEquals(Action.TRAVEL_TO_STRONGHOLD, steps.getLast().action());
        // String and sticks in hand: the bow is made on the way.
        var bow = StrongholdRunPlanner.plan(travelling, endFight(Map.of(Need.STRING, 3, Need.STICKS, 3), Set.of(), false));
        assertEquals("BOW", bow.getFirst().item());
    }

    @Test
    void whatIsStillMissingIsGatheredBeforeGoingDownToTheStronghold() {
        var there = justLit().eyes(10).throwsRecorded(2).estimate().near().build();
        assertEquals(Stage.PROVISION, StrongholdRunPlanner.stage(there, endFight(Map.of(), Set.of(), true)));
        var steps = StrongholdRunPlanner.plan(there, endFight(Map.of(), Set.of(), true));
        assertTrue(steps.stream().allMatch(step -> step.action() == Action.FOLLOW_UP));
        assertEquals("STRING", steps.stream().filter(step -> step.item().equals("STRING")).findFirst().orElseThrow().item());
        // All in hand, or no longer due (the gathering stalled, or the descent has begun): down it goes.
        var all = Map.of(Need.BOW, 1, Need.ARROWS, 32, Need.BLOCKS, 64, Need.WATER_BUCKET, 1, Need.FOOD, 16, Need.BED, 1);
        assertEquals(Stage.DESCEND, StrongholdRunPlanner.stage(there, endFight(all, Set.of(), true)));
        assertEquals(Stage.DESCEND, StrongholdRunPlanner.stage(there, endFight(Map.of(), Set.of(), false)));
        assertEquals(Stage.DESCEND, StrongholdRunPlanner.stage(there), "with nothing queued, as before");
    }

    @Test
    void whatTheStrategistQueuesForTheNetherTripIsDueBeforeGoingThrough() {
        var ready = justLit().ready().build();
        var held = Map.of(Need.FOOD, 12, Need.BLOCKS, 48);
        var queue = TaskQueue.EMPTY.advise(List.of(new dev.kindred.core.guide.Want(Need.SHIELD, 1, Objective.NETHER_TRIP, "ghasts")));
        var trip = queue.chunks(Set.of(Objective.NETHER_TRIP), held);
        var provisions = new Provisions(trip, Map.of(Objective.NETHER_TRIP, trip), Set.of(), held);
        assertEquals(Stage.PREPARE, StrongholdRunPlanner.stage(ready, provisions), "a shield queued for the trip holds it back");
        assertTrue(StrongholdRunPlanner.plan(ready, provisions).stream().anyMatch(step -> step.item().equals("SHIELD")));
        // The gathering stalled (nothing gated): through it goes.
        assertEquals(Stage.NETHER, StrongholdRunPlanner.stage(ready, new Provisions(trip, Map.of(), Set.of(), held)));
    }

    /** The guide's list of what a Nether trip needs uses the same amounts as the plan. */
    @Test
    void theGuidesNetherTripNeedsAreThePlannersOwn() {
        var needs = dev.kindred.core.guide.MinecraftGuide.requirements(Objective.NETHER_TRIP);
        assertTrue(needs.contains(new dev.kindred.core.guide.MinecraftGuide.Requirement(Need.FOOD, StrongholdRunPlanner.PREP_FOOD, "the Nether has little to eat")));
        assertEquals(StrongholdRunPlanner.PREP_BLOCKS, needs.stream().filter(r -> r.need() == Need.BLOCKS).findFirst().orElseThrow().count());
    }
}
