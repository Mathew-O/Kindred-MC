package dev.kindred.core.progression;

import dev.kindred.core.CanonicalRoster;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaystyleProfileTest {

    @Test
    void newPlayersDefaultToIdleObserving() {
        var settings = CanonicalRoster.RuntimeSettings.defaultsForNewPlayer();
        assertEquals(PlaystyleProfile.IDLE_OBSERVING, settings.playstyleProfile());
        assertTrue(settings.playstyleProfile().isIdleObserving());
        assertFalse(settings.seedLocatorEnabled());
        assertEquals(40, settings.idleRadiusBlocks());
    }

    @Test
    void idleModeSuppressesProgressionFlagsButKeepsQuotaFallback() {
        assertFalse(PlaystyleProfile.IDLE_OBSERVING.buildPermanentArchitecture());
        assertEquals(5, PlaystyleProfile.IDLE_OBSERVING.initialWoodLogsQuota());
        assertEquals(3, PlaystyleProfile.SPEEDRUN_ANY_PERCENT.initialWoodLogsQuota());
    }
}
