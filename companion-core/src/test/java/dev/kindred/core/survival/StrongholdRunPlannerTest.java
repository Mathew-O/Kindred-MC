package dev.kindred.core.survival;

import dev.kindred.core.survival.StrongholdRunPlanner.Action;
import dev.kindred.core.survival.StrongholdRunPlanner.Stage;
import dev.kindred.core.survival.StrongholdRunSnapshot.Dimension;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StrongholdRunPlannerTest {
    /** A player just through lighting its portal: a stone sword and pickaxe, little food, few blocks. */
    private static Builder justLit() {
        return new Builder();
    }

    private static final class Builder {
        Dimension dimension = Dimension.OVERWORLD;
        boolean portalKnown = true, fortressSeen, blazeSeen, endermanSeen, stalled, estimate, near, strongholdSeen, roomSeen;
        int food = 3, foodLevel = 20, sword = 2, pickaxe = 2, pickaxes = 1, armor = 1, shields = 0, blocks = 10, iron = 0;
        int rods, powder, pearls, eyes, throwsRecorded, filled;

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

        StrongholdRunSnapshot build() {
            return new StrongholdRunSnapshot(dimension, portalKnown, food, foodLevel, sword, pickaxe, pickaxes, armor, shields, blocks, iron,
                    rods, powder, pearls, eyes, fortressSeen, blazeSeen, endermanSeen, stalled, throwsRecorded, estimate, near,
                    strongholdSeen, roomSeen, filled);
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
        assertTrue(actions(s).containsAll(List.of(Action.HUNT, Action.SEEK_FOOD, Action.GATHER_STONE)));
        var ready = justLit().ready().build();
        assertEquals(Stage.NETHER, StrongholdRunPlanner.stage(ready));
        assertEquals(List.of(Action.ENTER_PORTAL), actions(ready));
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
}
