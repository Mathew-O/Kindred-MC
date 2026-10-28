package dev.kindred.core.progression;

import dev.kindred.core.CanonicalRoster;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: checks the playstyles: relaxed idling, steady survival and speedrunning.
 */
class PlaystyleProfileTest {

    @Test
    void newPlayersDefaultToIdleObserving() {
        var settings = CanonicalRoster.RuntimeSettings.defaultsForNewPlayer();
        assertEquals(PlaystyleProfile.IDLE_OBSERVING, settings.playstyleProfile());
        assertTrue(settings.playstyleProfile().isIdleObserving());
        assertEquals(40, settings.idleRadiusBlocks());
    }

    @Test
    void idleModeSuppressesProgressionFlagsButKeepsQuotaFallback() {
        assertFalse(PlaystyleProfile.IDLE_OBSERVING.buildPermanentArchitecture());
        assertEquals(5, PlaystyleProfile.IDLE_OBSERVING.initialWoodLogsQuota());
        assertEquals(3, PlaystyleProfile.SPEEDRUN_ANY_PERCENT.initialWoodLogsQuota());
    }
}
