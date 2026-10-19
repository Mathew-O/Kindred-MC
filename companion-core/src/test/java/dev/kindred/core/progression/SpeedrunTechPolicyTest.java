package dev.kindred.core.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedrunTechPolicyTest {

    @Test
    void buriedTreasureUsesChunkLocalNineNine() {
        assertArrayEquals(new int[]{9, 9}, SpeedrunTechPolicy.buriedTreasureBlockXZ(0, 0));
        assertArrayEquals(new int[]{25, 41}, SpeedrunTechPolicy.buriedTreasureBlockXZ(1, 2));
    }

    @Test
    void strongholdStaircaseUsesChunkLocalFourFour() {
        assertArrayEquals(new int[]{4, 4}, SpeedrunTechPolicy.strongholdStaircaseBlockXZ(0, 0));
    }

    @Test
    void stringToWoolCraftingCap() {
        assertEquals(2, SpeedrunTechPolicy.craftableWoolFromString(0, 10));
        assertEquals(1, SpeedrunTechPolicy.craftableWoolFromString(2, 8));
    }

    @Test
    void communityTechRequiresSpeedrunModeAndToggle() {
        assertFalse(SpeedrunTechPolicy.communityTechAllowed(PlaystyleProfile.IDLE_OBSERVING, true));
        assertFalse(SpeedrunTechPolicy.communityTechAllowed(PlaystyleProfile.OPTIMAL_SURVIVAL, true));
        assertTrue(SpeedrunTechPolicy.communityTechAllowed(PlaystyleProfile.SPEEDRUN_ANY_PERCENT, true));
        assertFalse(SpeedrunTechPolicy.communityTechAllowed(PlaystyleProfile.SPEEDRUN_ANY_PERCENT, false));
    }
}
