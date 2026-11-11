package dev.kindred.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Faction walks waiting as a whole (F-35i): three refused in a row, whatever the places, and none is asked for a while;
 * the wait ends early once it walks somewhere, moves 32 blocks off, or comes up to the surface having begun below. The
 * surface case is the one that matters in a tunnel (Factions seed 109: Kindred branch mining at Y 19); "at the surface"
 * itself, leaves not counted, is the adapter's {@code FactionsPlayer.atSurface}, read from the world's heightmap.
 */
class FactionsPlayerBackOffTest {
    private static final BlockPos TUNNEL = new BlockPos(105, 19, 64);

    private static FactionsPlayer refusedThrice(long tick) {
        var play = new FactionsPlayer();
        play.refused("minecraft:overworld:9:5", tick, TUNNEL, false);
        play.refused("minecraft:overworld:10:4", tick + 10, TUNNEL.east(2), false);
        play.refused("minecraft:overworld:9:6", tick + 20, TUNNEL.east(4), false);
        return play;
    }

    @Test
    void threeRefusedInARowWhateverThePlacesAndEveryFactionWalkWaits() {
        var play = new FactionsPlayer();
        play.refused("minecraft:overworld:9:5", 1000, TUNNEL, false);
        play.refused("minecraft:overworld:10:4", 1010, TUNNEL, false);
        assertFalse(play.paused(1011, TUNNEL, false), "two refused: not yet");
        play.refused("minecraft:overworld:9:6", 1020, TUNNEL, false);
        assertTrue(play.paused(1021, TUNNEL, false), "three refused, three places: every faction walk waits");
        assertTrue(play.paused(1020 + FactionsPlayer.PAUSE - 1, TUNNEL, false), "for the first wait");
        assertFalse(play.paused(1020 + FactionsPlayer.PAUSE, TUNNEL, false), "and no longer");
    }

    @Test
    void comingUpToTheSurfaceFromBelowEndsTheWaitWithinItsFirstStep() {
        var play = refusedThrice(1000);
        assertTrue(play.paused(1030, TUNNEL.east(6), false), "still in the tunnel: waiting");
        // Up the shaft it dug, at the surface over the tunnel: not 32 blocks off, but come up.
        BlockPos top = new BlockPos(111, 63, 64);
        assertFalse(play.paused(1040, top, true), "at the surface, the wait ends at once, within the first step of "
                + FactionsPlayer.PAUSE + " ticks");
        play.refused("minecraft:overworld:9:5", 1050, top, true);
        assertFalse(play.paused(1051, top, true), "the run begun afresh up there: one refusal is no wait");
    }

    @Test
    void aRunBegunAtTheSurfaceIsNotEndedByStandingAtTheSurface() {
        var play = new FactionsPlayer();
        BlockPos island = new BlockPos(0, 100, 0);
        for (int i = 0; i < 3; i++) play.refused("minecraft:overworld:" + i + ":0", 1000 + i, island, true);
        assertTrue(play.paused(1010, island.east(3), true), "on its island, at the surface throughout: still waiting");
    }

    @Test
    void movingMoreThan32BlocksEndsTheWaitAndLessDoesNot() {
        var play = refusedThrice(1000);
        assertTrue(play.paused(1030, TUNNEL.west(30), false), "30 blocks along its tunnel: still waiting");
        assertFalse(play.paused(1040, TUNNEL.west(40), false), "40 blocks off: the wait ends");
    }

    @Test
    void aWalkThatGotSomewhereEndsTheWait() {
        var play = refusedThrice(1000);
        play.walked("minecraft:overworld:9:5");
        assertFalse(play.paused(1030, TUNNEL, false));
    }

    @Test
    void eachRefusalAfterAWaitWaitsAgainTwiceAsLongUpToAGameDay() {
        var play = refusedThrice(1000);
        long tick = 1020 + FactionsPlayer.PAUSE;
        long wait = FactionsPlayer.PAUSE;
        for (int i = 0; i < 8; i++) {
            assertFalse(play.paused(tick, TUNNEL, false), "wait " + i + " over");
            play.refused("minecraft:overworld:9:5", tick, TUNNEL, false);
            wait = Math.min(FactionsPlayer.BACKOFF_MAX, wait * 2);
            assertTrue(play.paused(tick + wait - 1, TUNNEL, false), "one refusal after a wait: waits again, " + wait + " ticks");
            tick += wait;
        }
        assertEquals(FactionsPlayer.BACKOFF_MAX, wait, "a game day at most");
    }
}
