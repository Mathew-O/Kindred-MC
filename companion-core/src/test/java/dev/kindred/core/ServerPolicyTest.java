package dev.kindred.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the server's switches for free travel and free items (the owner's mod-support decision 6): both
 * are forbidden unless an operator allows them, changing the edit switch keeps them, and the rule is announced to every
 * player in the same words, naming humans and AI players alike (D6).
 */
class ServerPolicyTest {
    @Test
    void freeTravelAndFreeItemsAreForbiddenByDefault() {
        for (var policy : new EditPolicy.ServerPolicy[] {EditPolicy.ServerPolicy.deferredD4(), new EditPolicy.ServerPolicy(true, true)}) {
            assertFalse(policy.teleport());
            assertFalse(policy.freeItems());
        }
    }

    @Test
    void theEditSwitchKeepsTheTravelAndItemSwitches() {
        var policy = EditPolicy.ServerPolicy.deferredD4().withTeleport(true);
        var edited = policy.withEdits(true);
        assertTrue(edited.autonomousEditsEnabled() && edited.protectionCompatibilityKnown());
        assertTrue(edited.teleport());
        assertFalse(edited.freeItems());
        assertTrue(edited.withFreeItems(true).withEdits(false).freeItems());
    }

    @Test
    void theRuleIsAnnouncedForEveryoneAlike() {
        var forbidden = EditPolicy.ServerPolicy.deferredD4().announcement();
        var allowed = EditPolicy.ServerPolicy.deferredD4().withTeleport(true).withFreeItems(true).announcement();
        assertEquals(2, forbidden.size());
        assertTrue(forbidden.get(0).startsWith("No teleporting") && forbidden.get(1).startsWith("No free items"));
        assertTrue(allowed.get(0).startsWith("Teleporting is allowed") && allowed.get(1).startsWith("Free items"));
        for (String line : forbidden) assertTrue(line.contains("human or AI"), line);
        for (String line : allowed) assertTrue(line.contains("human or AI"), line);
    }
}
