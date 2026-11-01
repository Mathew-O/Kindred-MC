package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: checks the starter players' names: Echo answers to its old name, Vesp, so a world that already
 * has Vesp finds it again instead of making a second player, and the rename keeps its id.
 */
class CanonicalRosterTest {

    @Test
    void echoAnswersToItsNameAndItsFormerName() {
        assertEquals("Echo", CanonicalRoster.ECHO.name());
        assertTrue(CanonicalRoster.ECHO.answersTo("Echo"));
        assertTrue(CanonicalRoster.ECHO.answersTo(" vesp "));
        assertFalse(CanonicalRoster.ECHO.answersTo("Sol"));
        assertFalse(CanonicalRoster.SOL.answersTo("Vesp"));
        assertFalse(CanonicalRoster.ECHO.answersTo(null));
        assertEquals(CanonicalRoster.ECHO, CanonicalRoster.forName("VESP").orElseThrow());
        assertEquals(CanonicalRoster.KINDRED, CanonicalRoster.forName("kindred").orElseThrow());
        assertTrue(CanonicalRoster.forName("Ada").isEmpty());
        assertEquals(java.util.List.of("Kindred", "Sol", "Echo"),
                CanonicalRoster.TRIO.stream().map(CanonicalRoster.RosterPreset::name).toList());
    }

    @Test
    void theRenameKeepsEchosIdInEveryWorld() {
        UUID world = new UUID(7, 11);
        var asVesp = new CanonicalRoster.RosterPreset("Vesp", "", Personality.defaults());
        assertEquals(asVesp.deterministicId(world), CanonicalRoster.ECHO.deterministicId(world));
        assertFalse(CanonicalRoster.ECHO.deterministicId(world).equals(CanonicalRoster.SOL.deterministicId(world)));
    }
}
