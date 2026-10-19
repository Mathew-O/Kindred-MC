package dev.kindred.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalRosterSkinsTest {

    @Test
    void resolvesCanonicalDisplayNames() {
        assertEquals(CanonicalRosterSkins.KINDRED, CanonicalRosterSkins.forDisplayName("Kindred").orElseThrow());
        assertEquals(CanonicalRosterSkins.SOL, CanonicalRosterSkins.forDisplayName("sol").orElseThrow());
        assertEquals(CanonicalRosterSkins.VESP, CanonicalRosterSkins.forDisplayName(" Vesp ").orElseThrow());
        assertTrue(CanonicalRosterSkins.forDisplayName("CustomBot").isEmpty());
    }
}