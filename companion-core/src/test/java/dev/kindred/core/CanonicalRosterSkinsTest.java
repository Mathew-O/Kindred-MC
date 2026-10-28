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

    @Test
    void profileMarkerRoundTripsToTheBundledSlimSkin() {
        for (var skin : new CanonicalRosterSkins.SkinBinding[]{CanonicalRosterSkins.KINDRED, CanonicalRosterSkins.SOL, CanonicalRosterSkins.VESP}) {
            assertEquals(skin, CanonicalRosterSkins.forKey(skin.key()).orElseThrow());
            assertTrue(skin.slim(), skin.key() + " ships a slim-armed skin");
            assertEquals("textures/entity/roster/" + skin.key() + ".png", skin.clientAssetPath());
        }
        assertTrue(CanonicalRosterSkins.forKey("steve").isEmpty());
    }
}
