package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks what players say to people, and that it stays polite and within its limits.
 */
class SocialDialogueTest {
    private final SocialProfile profile = SocialProfile.defaults(UUID.randomUUID());
    private final SocialSnapshot stopped = new SocialSnapshot(false, false, false, false, null, List.of());

    @Test
    void onlyExplicitAddressedMessagesMatch() {
        assertEquals("hello", SocialDialogue.addressedMessage("Juniper", "jUnIpEr, hello").orElseThrow());
        assertEquals("hi", SocialDialogue.addressedMessage("June Oak", "June Oak: hi").orElseThrow());
        assertTrue(SocialDialogue.addressedMessage("Juniper", "I saw Juniper today").isEmpty());
        assertTrue(SocialDialogue.addressedMessage("Juniper", "Juniperish: hi").isEmpty());
        assertTrue(SocialDialogue.addressedMessage("Juniper", "Juniper: ").isEmpty());
        assertTrue(SocialDialogue.addressedMessage("Juniper", "Juniper: " + "x".repeat(256)).isEmpty());
    }

    @Test
    void controlWordsStayInConversationLaneAndDoNotClaimAction() {
        for (String control : List.of("follow me", "stop", "gather 8 logs", "build a dock", "mine")) {
            assertTrue(SocialDialogue.reply(control, stopped, profile).contains("Conversation doesn't change my tasks"));
        }
        assertTrue(SocialDialogue.reply("what are you doing", stopped, profile).contains("staying put"));
        assertFalse(SocialDialogue.reply("remember when we built a dock?", stopped, profile).contains("we built"));
    }

    @Test
    void repliesUseObservedFactsAndHumorDoesNotChangeThem() {
        var observed = new SocialSnapshot(true, true, true, true, "Skeleton", List.of());
        assertTrue(SocialDialogue.reply("weather", observed, profile).contains("raining"));
        assertTrue(SocialDialogue.reply("how are you?", observed, profile).contains("damage"));
        assertTrue(SocialDialogue.reply("what do you see", observed, profile.withTrait(Personality.Trait.HUMOR, 100))
                .contains("Skeleton"));
        assertNotEquals(SocialDialogue.reply("hello", observed, profile.withTrait(Personality.Trait.HUMOR, 100)),
                SocialDialogue.reply("hello", observed, profile.withTrait(Personality.Trait.HUMOR, 0)));
    }
}
