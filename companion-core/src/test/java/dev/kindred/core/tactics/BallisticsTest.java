package dev.kindred.core.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the player's aim with a bow and with ender pearls: it works out the angle that lands
 * a shot on the target, high targets such as crystals on their pillars included, and gives up when the target
 * is out of reach.
 */
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

    @Test
    void aPearlFliesSlowerAndFallsLessAndReachesAboutThirtyBlocksUp() {
        var pearl = Ballistics.Projectile.PEARL;
        // Twelve blocks off and eight up, a pearl's low arc lands there; slower than an arrow, it takes longer.
        var shot = Ballistics.aim(12, 8, pearl, false).orElseThrow();
        assertEquals(8, Ballistics.heightAt(shot.pitch(), pearl, 12).orElseThrow()[0], 0.05);
        assertTrue(shot.ticks() > Ballistics.aim(12, 8, Ballistics.Projectile.ARROW, false).orElseThrow().ticks());
        // About thirty blocks up is within a pearl's reach from close by, forty is not.
        assertTrue(Ballistics.aim(3, 28, pearl, false).isPresent(), "a caged pillar's roof from its foot");
        assertTrue(Ballistics.aim(3, 45, pearl, false).isEmpty(), "out of reach above");
        // Down from a pillar, twenty blocks out and twenty below.
        var down = Ballistics.aim(20, -20, pearl, false).orElseThrow();
        assertEquals(-20, Ballistics.heightAt(down.pitch(), pearl, 20).orElseThrow()[0], 0.05);
    }

    @Test
    void theHighArcComesDownSteeplyOnTheSameTarget() {
        var low = Ballistics.aim(30, 10, Ballistics.Projectile.ARROW, false).orElseThrow();
        var high = Ballistics.aim(30, 10, Ballistics.Projectile.ARROW, true).orElseThrow();
        assertTrue(high.pitch() < low.pitch() - 20, "the high arc is aimed far higher: " + high.pitch() + " against " + low.pitch());
        assertTrue(high.ticks() > low.ticks(), "and arrives later");
        assertEquals(10, Ballistics.heightAt(high.pitch(), Ballistics.Projectile.ARROW, 30).orElseThrow()[0], 0.05);
        // Descending as it arrives: just short of the target it was higher.
        var arc = Ballistics.trajectory(high.pitch(), Ballistics.Projectile.ARROW, 30);
        assertTrue(arc.get(arc.size() - 2)[1] > arc.getLast()[1], "coming down onto it");
    }
}
