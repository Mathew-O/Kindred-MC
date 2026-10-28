package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks what a player says and does when someone remarks on one of its habits.
 */
class QuirkFeedbackTest {
    private static final Personality NEUTRAL = Personality.defaults();

    @Test
    void detectsRemarksAboutHabitsAndIgnoresUnrelatedOrInstructionalChat() {
        for (String remark : List.of("sprint jumping wastes hunger, you know", "why do you keep jumping everywhere?",
                "stop hopping around please", "all that jumping isn't optimal for food")) {
            assertEquals(Mannerisms.Quirk.SPRINTJUMP, QuirkFeedback.detect(remark).orElseThrow(), remark);
        }
        assertEquals(Mannerisms.Quirk.LIVELY, QuirkFeedback.detect("can you sit still for a second").orElseThrow());
        assertEquals(Mannerisms.Quirk.CURIOUSGLANCE, QuirkFeedback.detect("quit staring at me, it's creepy").orElseThrow());
        assertEquals(Mannerisms.Quirk.STEADY, QuirkFeedback.detect("you're so stiff, loosen up").orElseThrow());
        for (String other : List.of("jump over here", "hello", "what do you see", "the creeper jumped me", "", "x".repeat(300))) {
            assertTrue(QuirkFeedback.detect(other).isEmpty(), other);
        }
    }

    @Test
    void decisionFollowsTheCompanionsOwnTraitsNotTheSpeakersInsistence() {
        assertEquals(50, QuirkFeedback.accommodation(NEUTRAL));
        var neutralOwner = QuirkFeedback.respond(Mannerisms.Quirk.SPRINTJUMP, NEUTRAL, QuirkFeedback.Speaker.OWNER, true);
        assertEquals(QuirkFeedback.Decision.KEEP, neutralOwner.decision());
        assertTrue(neutralOwner.reply().contains("/companion quirk sprintjump off"), "the owner is told the real control");
        var neutralPlayer = QuirkFeedback.respond(Mannerisms.Quirk.SPRINTJUMP, NEUTRAL, QuirkFeedback.Speaker.PLAYER, true);
        assertEquals(QuirkFeedback.Decision.KEEP, neutralPlayer.decision());
        assertFalse(neutralPlayer.reply().contains("/companion"), "visitors are not handed the owner's controls");

        Personality agreeable = NEUTRAL.withTrait(Personality.Trait.HELPFULNESS, 80).withTrait(Personality.Trait.CAUTION, 60);
        assertEquals(68, QuirkFeedback.accommodation(agreeable));
        assertEquals(QuirkFeedback.Decision.ACCOMMODATE, QuirkFeedback.respond(Mannerisms.Quirk.SPRINTJUMP, agreeable, QuirkFeedback.Speaker.OWNER, true).decision());
        assertEquals(QuirkFeedback.OWNER_ACCOMMODATION_MILLIS, QuirkFeedback.respond(Mannerisms.Quirk.SPRINTJUMP, agreeable, QuirkFeedback.Speaker.OWNER, true).accommodationMillis());
        assertEquals(QuirkFeedback.Decision.KEEP, QuirkFeedback.respond(Mannerisms.Quirk.SPRINTJUMP, agreeable, QuirkFeedback.Speaker.PLAYER, true).decision());

        Personality pushover = agreeable.withTrait(Personality.Trait.INITIATIVE, 10);
        assertEquals(76, QuirkFeedback.accommodation(pushover));
        var fromPlayer = QuirkFeedback.respond(Mannerisms.Quirk.SPRINTJUMP, pushover, QuirkFeedback.Speaker.PLAYER, true);
        assertEquals(QuirkFeedback.Decision.ACCOMMODATE, fromPlayer.decision());
        assertEquals(QuirkFeedback.PLAYER_ACCOMMODATION_MILLIS, fromPlayer.accommodationMillis());

        Personality headstrong = NEUTRAL.withTrait(Personality.Trait.INITIATIVE, 100).withTrait(Personality.Trait.HELPFULNESS, 20);
        assertEquals(QuirkFeedback.Decision.KEEP, QuirkFeedback.respond(Mannerisms.Quirk.SPRINTJUMP, headstrong, QuirkFeedback.Speaker.OWNER, true).decision());
    }

    @Test
    void companionAndModelSpeechNeverChangeBehaviorAndInactiveHabitsGetAnHonestAnswer() {
        Personality pushover = NEUTRAL.withTrait(Personality.Trait.HELPFULNESS, 100).withTrait(Personality.Trait.CAUTION, 100)
                .withTrait(Personality.Trait.INITIATIVE, 0);
        for (var speaker : List.of(QuirkFeedback.Speaker.COMPANION, QuirkFeedback.Speaker.MODEL)) {
            var response = QuirkFeedback.respond(Mannerisms.Quirk.LIVELY, pushover, speaker, true);
            assertEquals(QuirkFeedback.Decision.NONE, response.decision());
            assertEquals(0, response.accommodationMillis());
        }
        var inactive = QuirkFeedback.respond(Mannerisms.Quirk.SPRINTJUMP, pushover, QuirkFeedback.Speaker.OWNER, false);
        assertEquals(QuirkFeedback.Decision.NONE, inactive.decision());
        assertTrue(inactive.reply().toLowerCase().contains("not"));
    }

    @Test
    void humorChangesWordingButNeverTheDecision() {
        Personality dryAgreeable = NEUTRAL.withTrait(Personality.Trait.HELPFULNESS, 90).withTrait(Personality.Trait.HUMOR, 0);
        Personality wittyAgreeable = dryAgreeable.withTrait(Personality.Trait.HUMOR, 100);
        for (Mannerisms.Quirk quirk : Mannerisms.Quirk.values()) {
            for (var speaker : List.of(QuirkFeedback.Speaker.OWNER, QuirkFeedback.Speaker.PLAYER)) {
                var dry = QuirkFeedback.respond(quirk, dryAgreeable, speaker, true);
                var witty = QuirkFeedback.respond(quirk, wittyAgreeable, speaker, true);
                assertEquals(dry.decision(), witty.decision());
                assertEquals(dry.accommodationMillis(), witty.accommodationMillis());
                assertNotEquals(dry.reply(), witty.reply());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new QuirkFeedback.Response(Mannerisms.Quirk.LIVELY, QuirkFeedback.Decision.KEEP, 5, "x"));
        assertThrows(IllegalArgumentException.class, () -> new QuirkFeedback.Response(Mannerisms.Quirk.LIVELY, QuirkFeedback.Decision.ACCOMMODATE, 0, "x"));
    }
}
