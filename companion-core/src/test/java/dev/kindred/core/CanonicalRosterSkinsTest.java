package dev.kindred.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: checks that Kindred, Sol and Echo each get their own bundled skin, and that a player saved under
 * Echo's former name, Vesp, still gets Echo's.
 */
class CanonicalRosterSkinsTest {

    @Test
    void resolvesCanonicalDisplayNames() {
        assertEquals(CanonicalRosterSkins.KINDRED, CanonicalRosterSkins.forDisplayName("Kindred").orElseThrow());
        assertEquals(CanonicalRosterSkins.SOL, CanonicalRosterSkins.forDisplayName("sol").orElseThrow());
        assertEquals(CanonicalRosterSkins.ECHO, CanonicalRosterSkins.forDisplayName("Echo").orElseThrow());
        assertEquals(CanonicalRosterSkins.ECHO, CanonicalRosterSkins.forDisplayName(" Vesp ").orElseThrow());
        assertTrue(CanonicalRosterSkins.forDisplayName("CustomBot").isEmpty());
    }

    @Test
    void profileMarkerRoundTripsToTheBundledSlimSkin() {
        for (var skin : new CanonicalRosterSkins.SkinBinding[]{CanonicalRosterSkins.KINDRED, CanonicalRosterSkins.SOL, CanonicalRosterSkins.ECHO}) {
            assertEquals(skin, CanonicalRosterSkins.forKey(skin.key()).orElseThrow());
            assertTrue(skin.slim(), skin.key() + " ships a slim-armed skin");
            assertEquals("textures/entity/roster/" + skin.key() + ".png", skin.clientAssetPath());
        }
        assertEquals(CanonicalRosterSkins.ECHO, CanonicalRosterSkins.forKey("echo").orElseThrow());
        assertTrue(CanonicalRosterSkins.forKey("steve").isEmpty());
    }
}
