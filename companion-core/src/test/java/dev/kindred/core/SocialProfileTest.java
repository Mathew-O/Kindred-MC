package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that giving a player a whole new personality keeps its other social settings and
 * counts as a change to both its profile and its personality.
 */
class SocialProfileTest {
    @Test
    void aWholePersonalityIsTakenWithItsSettingsKeptAndBothRevisionsMovedOn() {
        var profile = SocialProfile.defaults(UUID.randomUUID()).withPublic(true);
        var preset = CanonicalRoster.SOL.defaultPersonality();
        var next = profile.withPersonality(preset);
        for (var trait : Personality.Trait.values()) assertEquals(preset.value(trait), next.personality().value(trait), trait.name());
        assertTrue(next.publicConversation() && next.ambientEnabled(), "its other settings kept");
        assertEquals(profile.revision() + 1, next.revision(), "a profile change like any other");
        assertEquals(profile.personality().revision() + 1, next.personality().revision(), "the personality's own revision moves on too");
        assertEquals(profile.companionId(), next.companionId());
    }
}
