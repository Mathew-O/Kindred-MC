package dev.kindred.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Faction walks waiting as a whole (F-35i): two places each refused three times and set aside with no walk between,
 * and none is asked for a while; each place keeps its own three tries ({@code NativeFactionPlayGameTests}' no-way
 * fixture);
 * the wait ends early once it walks somewhere, moves 32 blocks off, or comes up to the surface having begun below. The
 * surface case is the one that matters in a tunnel (Factions seed 109: Kindred branch mining at Y 19); "at the surface"
 * itself, leaves not counted, is the adapter's {@code FactionsPlayer.atSurface}, read from the world's heightmap.
 */
class FactionsPlayerBackOffTest {
    private static final BlockPos TUNNEL = new BlockPos(105, 19, 64);

    /** Two places refused three times each from the tunnel, the last at {@code tick + 20}: both set aside. */
    private static FactionsPlayer refusedThrice(long tick) {
        var play = new FactionsPlayer();
        for (int i = 0; i < 3; i++) {
            play.refused("minecraft:overworld:9:5", tick, TUNNEL.east(i), false);
            play.refused("minecraft:overworld:10:4", tick + 10 + 10 * i / 2, TUNNEL.east(i), false);
        }
        return play;
    }

    @Test
    void twoPlacesSetAsideWithNothingBetweenAndEveryFactionWalkWaits() {
        var play = new FactionsPlayer();
        for (int i = 0; i < 3; i++) play.refused("minecraft:overworld:9:5", 1000 + i, TUNNEL, false);
        assertFalse(play.paused(1003, TUNNEL, false), "one place set aside: each place keeps its own three tries");
        play.refused("minecraft:overworld:10:4", 1010, TUNNEL, false);
        play.refused("minecraft:overworld:10:4", 1011, TUNNEL, false);
        assertFalse(play.paused(1012, TUNNEL, false), "the second place twice: not yet");
        assertEquals(2, play.refusals("minecraft:overworld:10:4"));
        play.refused("minecraft:overworld:10:4", 1020, TUNNEL, false);
        assertEquals(3, play.refusals("minecraft:overworld:10:4"), "the second place had its three tries");
        assertTrue(play.paused(1021, TUNNEL, false), "two places set aside: every faction walk waits");
        assertTrue(play.paused(1020 + FactionsPlayer.PAUSE - 1, TUNNEL, false), "for the first wait");
        assertFalse(play.paused(1020 + FactionsPlayer.PAUSE, TUNNEL, false), "and no longer");
    }

    @Test
    void aWalkCompletedBetweenTheTwoSetAsidesStartsNoWait() {
        var play = new FactionsPlayer();
        for (int i = 0; i < 3; i++) play.refused("minecraft:overworld:9:5", 1000 + i, TUNNEL, false);
        play.walked("minecraft:overworld:3:3");
        for (int i = 0; i < 3; i++) play.refused("minecraft:overworld:10:4", 1010 + i, TUNNEL, false);
        assertFalse(play.paused(1013, TUNNEL, false), "a walk got somewhere between: the run began again, one place set aside since");
        for (int i = 0; i < 3; i++) play.refused("minecraft:overworld:9:6", 1020 + i, TUNNEL, false);
        assertTrue(play.paused(1023, TUNNEL, false), "a second place set aside since the walk: every faction walk waits");
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
        for (int i = 0; i < 3; i++) for (int place = 0; place < 2; place++) play.refused("minecraft:overworld:" + place + ":0", 1000 + i, island, true);
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
