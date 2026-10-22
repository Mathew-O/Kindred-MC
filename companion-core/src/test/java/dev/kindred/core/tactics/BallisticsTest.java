package dev.kindred.core.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BallisticsTest {
    private static double landing(Ballistics.Shot shot, double distance) {
        return Ballistics.heightAt(shot.pitch(), Ballistics.FULL_DRAW, distance).orElseThrow()[0];
    }

    @Test
    void aTargetLevelWithTheBowIsHitWithALittleLift() {
        var shot = Ballistics.aim(20, 0, Ballistics.FULL_DRAW).orElseThrow();
        assertTrue(shot.pitch() < 0 && shot.pitch() > -5, "a little above the horizon: " + shot.pitch());
        assertEquals(0, landing(shot, 20), 0.05);
        assertTrue(shot.ticks() >= 6 && shot.ticks() <= 9, "twenty blocks in about seven ticks: " + shot.ticks());
    }

    @Test
    void aCrystalHighOnItsPillarIsHitFromTheGround() {
        // A crystal 35 blocks up, 45 off: the arc meets it, the low one.
        var shot = Ballistics.aim(45, 35, Ballistics.FULL_DRAW).orElseThrow();
        assertEquals(35, landing(shot, 45), 0.05);
        assertTrue(shot.pitch() < -35 && shot.pitch() > -60, "steeply up, the low arc: " + shot.pitch());
        // Below the bow, it aims down.
        var down = Ballistics.aim(10, -6, Ballistics.FULL_DRAW).orElseThrow();
        assertTrue(down.pitch() > 0, "down: " + down.pitch());
        assertEquals(-6, landing(down, 10), 0.05);
    }

    @Test
    void outOfReachThereIsNoShot() {
        assertTrue(Ballistics.aim(400, 0, Ballistics.FULL_DRAW).isEmpty(), "far beyond any arrow's flight");
        assertTrue(Ballistics.aim(10, 90, Ballistics.FULL_DRAW).isEmpty(), "far too high");
        assertTrue(Ballistics.aim(0, 5, Ballistics.FULL_DRAW).isEmpty());
    }

    @Test
    void theArcEndsWhereTheShotMeetsItsTarget() {
        var shot = Ballistics.aim(30, 12, Ballistics.FULL_DRAW).orElseThrow();
        var arc = Ballistics.trajectory(shot.pitch(), Ballistics.FULL_DRAW, 30);
        assertEquals(30, arc.getLast()[0], 1e-9);
        assertEquals(12, arc.getLast()[1], 0.05);
        assertTrue(arc.stream().anyMatch(p -> p[1] > 12), "it rises above the line and comes down onto the target");
    }
}
