package dev.kindred.core.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FallClutchTest {
    @Test
    void aFallHurtsAPointABlockPastThree() {
        assertEquals(0, FallClutch.damage(3));
        assertEquals(1, FallClutch.damage(3.5));
        assertEquals(17, FallClutch.damage(20), "a tower twenty blocks up: nearly all of a player's twenty");
    }

    @Test
    void onlyAFallThatWouldHurtIsWorthBreaking() {
        assertFalse(FallClutch.worth(2, 3, 20), "five blocks: two points");
        assertTrue(FallClutch.worth(4, 3, 20), "seven blocks: four points");
        assertTrue(FallClutch.worth(2, 3, 4), "two points of the four it has left");
        assertTrue(FallClutch.worth(1, 19, 20));
    }

    @Test
    void theWaterGoesDownAsSoonAsTheGroundIsInReach() {
        assertFalse(FallClutch.pourNow(3.0), "the face 4.62 from the eyes: out of reach");
        assertTrue(FallClutch.pourNow(2.8));
        assertTrue(FallClutch.pourNow(0.4));
        assertFalse(FallClutch.pourNow(-0.1), "below the face: landed or past it");
    }
}
