package dev.kindred.core.survival;

import dev.kindred.core.survival.EndFightPlanner.Action;
import dev.kindred.core.survival.EndFightPlanner.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EndFightPlannerTest {
    /** At the portal room with eleven eyes, two frames already filled, a bow, 32 arrows, 64 blocks and an iron sword. */
    private static final class Builder {
        boolean inEnd, killed, portalOpen, onIsland, alive = true, perched, breath, exitOpen, bow = true;
        int framesSeen = 12, framesFilled = 2, eyes = 11, crystals = 10, shootable = 8, caged = 2, arrows = 32, blocks = 64, sword = 3;
        float health = 20;

        Builder end() { inEnd = true; portalOpen = true; framesFilled = 12; eyes = 1; return this; }
        Builder island() { onIsland = true; return this; }
        Builder crystals(int alive, int shootable, int caged) { crystals = alive; this.shootable = shootable; this.caged = caged; return this; }
        Builder perched() { perched = true; return this; }
        Builder breath() { breath = true; return this; }
        Builder dead() { alive = false; exitOpen = true; return this; }
        Builder noBow() { bow = false; arrows = 0; return this; }
        Builder health(float h) { health = h; return this; }

        EndFightSnapshot build() {
            return new EndFightSnapshot(inEnd, killed, framesSeen, framesFilled, eyes, portalOpen, onIsland, crystals, shootable, caged,
                    alive, perched, breath, exitOpen, bow, arrows, blocks, sword, health);
        }
    }

    private static List<Action> actions(EndFightSnapshot s) {
        return EndFightPlanner.plan(s).stream().map(EndFightPlanner.Step::action).toList();
    }

    @Test
    void theFrameIsFilledThenTheEndIsEntered() {
        var room = new Builder().build();
        assertEquals(Stage.FILL_PORTAL, EndFightPlanner.stage(room));
        assertEquals(List.of(Action.FILL_FRAME), actions(room));
        assertEquals(10, room.eyesWanted());
        var open = new Builder();
        open.portalOpen = true;
        assertEquals(Stage.ENTER_END, EndFightPlanner.stage(open.build()));
        var none = new Builder();
        none.eyes = 0;
        assertEquals(Stage.SHORT_OF_EYES, EndFightPlanner.stage(none.build()), "no eyes left: back for more, not stuck at the frame");
    }

    @Test
    void inTheEndTheIslandThenTheCrystalsThenTheDragon() {
        assertEquals(Stage.REACH_ISLAND, EndFightPlanner.stage(new Builder().end().build()));
        var crystals = new Builder().end().island().build();
        assertEquals(Stage.CRYSTALS, EndFightPlanner.stage(crystals));
        assertEquals(List.of(Action.SHOOT_CRYSTAL, Action.OPEN_CAGE, Action.SEEK_CRYSTALS), actions(crystals));
        // Only caged ones left: open them; none left: the dragon.
        assertEquals(List.of(Action.OPEN_CAGE, Action.SEEK_CRYSTALS), actions(new Builder().end().island().crystals(2, 0, 2).build()));
        assertEquals(Stage.DRAGON, EndFightPlanner.stage(new Builder().end().island().crystals(0, 0, 0).build()));
        // One left that it has given up on for now: the dragon meanwhile, not a wait by the crystal.
        var givenUp = new Builder().end().island().crystals(1, 0, 0).build();
        assertEquals(Stage.DRAGON, EndFightPlanner.stage(givenUp));
        assertEquals(List.of(Action.SHOOT_DRAGON, Action.HOLD_GROUND), actions(givenUp));
        // Without a bow or blocks there is no way to break them: on to the dragon all the same.
        var bare = new Builder().end().island().noBow();
        bare.blocks = 4;
        assertEquals(Stage.DRAGON, EndFightPlanner.stage(bare.build()));
    }

    @Test
    void theDragonIsStruckOnItsPerchAndKeptClearOfOtherwise() {
        var flying = new Builder().end().island().crystals(0, 0, 0).build();
        assertEquals(List.of(Action.SHOOT_DRAGON, Action.HOLD_GROUND), actions(flying));
        var perched = new Builder().end().island().crystals(0, 0, 0).perched().build();
        assertEquals(Action.STRIKE_DRAGON, actions(perched).getFirst());
        // Out of its breath before anything else; hurt, it keeps clear until it heals.
        assertEquals(Action.LEAVE_BREATH, actions(new Builder().end().island().crystals(0, 0, 0).perched().breath().build()).getFirst());
        assertEquals(List.of(Action.HOLD_GROUND), actions(new Builder().end().island().crystals(0, 0, 0).perched().health(6).build()));
    }

    @Test
    void whenTheDragonIsDeadItGoesHome() {
        var won = new Builder().end().island().crystals(0, 0, 0).dead().build();
        assertEquals(Stage.EXIT, EndFightPlanner.stage(won));
        assertEquals(List.of(Action.ENTER_PORTAL), actions(won));
        var home = new Builder();
        home.killed = true;
        assertEquals(Stage.DONE, EndFightPlanner.stage(home.build()));
    }
}
