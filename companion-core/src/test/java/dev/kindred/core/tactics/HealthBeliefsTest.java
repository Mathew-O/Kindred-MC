package dev.kindred.core.tactics;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the player's guess at how hurt each creature is: one it never saw hit is taken to be
 * at full health, each hit it saw counts, players are expected to heal over time and mobs not, and the oldest
 * guesses are forgotten first.
 */
class HealthBeliefsTest {
    @Test
    void aCreatureNeverSeenHitIsBelievedAtItsKindsFullHealth() {
        var beliefs = new HealthBeliefs();
        var zombie = beliefs.of(UUID.randomUUID(), 20, false, 100);
        assertEquals(20, zombie.value());
        assertEquals(HealthBeliefs.Basis.INFERRED, zombie.basis(), "worked out, never read");
        assertTrue(zombie.confidence() > 0.8, "a mob is usually what its kind has");
        assertTrue(beliefs.of(UUID.randomUUID(), 20, true, 100).confidence() < 0.5, "a player may have been hurt anywhere");
        assertEquals(0, beliefs.size(), "asking remembers nothing");
    }

    @Test
    void eachHitSeenTakesOffWhatTheRulesetSaysAndAMobDoesNotHeal() {
        var beliefs = new HealthBeliefs();
        UUID zombie = UUID.randomUUID();
        // An iron sword at full charge on a zombie's two points of armour.
        double dealt = CombatRuleset.afterArmour(DamageModel.hit(new DamageModel.Weapon(DamageModel.Kind.SWORD, DamageModel.Tier.IRON, 0), 1, false, 0, 0), 2, 0);
        beliefs.hit(zombie, 20, false, dealt, 100);
        beliefs.hit(zombie, 20, false, dealt, 120);
        var after = beliefs.of(zombie, 20, false, 2000);
        assertEquals(20 - 2 * dealt, after.value(), 1e-9, "a mob does not heal");
        assertTrue(beliefs.seenHit(zombie));
        assertTrue(after.confidence() < beliefs.of(zombie, 20, false, 120).confidence(), "less sure as time passes");
        beliefs.hit(zombie, 20, false, 50, 2100);
        assertEquals(0, beliefs.of(zombie, 20, false, 2100).value(), "never below nothing");
    }

    @Test
    void aPlayerIsBelievedToHealAsTimePasses() {
        var beliefs = new HealthBeliefs();
        UUID player = UUID.randomUUID();
        beliefs.hit(player, 20, true, 8, 0);
        assertEquals(12, beliefs.of(player, 20, true, 0).value(), 1e-9);
        assertEquals(13, beliefs.of(player, 20, true, 80).value(), 1e-9, "about a point every four seconds");
        assertEquals(20, beliefs.of(player, 20, true, 100_000).value(), 1e-9, "never above full");
    }

    @Test
    void theLeastRecentlySeenAreForgottenFirst() {
        var beliefs = new HealthBeliefs();
        UUID first = UUID.randomUUID();
        beliefs.hit(first, 20, false, 1, 0);
        for (int i = 0; i < HealthBeliefs.CAPACITY; i++) beliefs.hit(UUID.randomUUID(), 20, false, 1, i);
        assertEquals(HealthBeliefs.CAPACITY, beliefs.size());
        assertFalse(beliefs.seenHit(first), "the oldest went");
        assertThrows(IllegalArgumentException.class, () -> beliefs.hit(UUID.randomUUID(), 20, false, -1, 0));
    }
}
