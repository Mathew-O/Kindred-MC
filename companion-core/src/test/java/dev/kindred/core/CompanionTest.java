package dev.kindred.core;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the basics of an AI player: a new one starts asleep, a name gives no control over
 * it, and its personality values stay in range.
 */
class CompanionTest {
    @Test
    void creationIsDormantAndNameDoesNotGrantOwnership() {
        UUID owner = UUID.randomUUID();
        Companion companion = Companion.create(UUID.randomUUID(), owner, "Alex",
                UUID.randomUUID(), "minecraft:overworld");
        assertEquals(LifecycleState.SLEEPING, companion.lifecycle());
        assertNull(companion.entityId());
        assertFalse(companion.stopLatched());
        assertTrue(Authorization.mayControl(Actor.player(owner), companion));
        assertFalse(Authorization.mayControl(Actor.player(UUID.randomUUID()), companion));
        assertTrue(Authorization.mayControl(Actor.console(), companion));
    }

    @Test
    void traitsValidateBoundsAndKeepIndependentValuesWithRevision() {
        Personality original = Personality.defaults();
        Personality edited = original.withTrait(Personality.Trait.INITIATIVE, 100)
                .withTrait(Personality.Trait.HUMOR, 0);
        assertEquals(2, edited.revision());
        assertEquals(1.0, edited.normalized(Personality.Trait.INITIATIVE));
        assertEquals(0.0, edited.normalized(Personality.Trait.HUMOR));
        assertEquals(50, edited.caution());
        assertEquals(50, original.initiative());
        assertThrows(IllegalArgumentException.class,
                () -> original.withTrait(Personality.Trait.CURIOSITY, -1));
        assertThrows(IllegalArgumentException.class,
                () -> original.withTrait(Personality.Trait.SOCIABILITY, 101));
    }

    @Test
    void onlyActiveAndReturningConsumeAwakeBudget() {
        for (LifecycleState state : LifecycleState.values()) {
            assertEquals(state == LifecycleState.ACTIVE || state == LifecycleState.RETURNING,
                    state.chargesAwakeBudget());
        }
    }

    @Test
    void everyPersonalityDimensionCanBeMixedAndValidatedIndependently() {
        Personality personality = Personality.defaults();
        long revision = 0;
        for (Personality.Trait trait : Personality.Trait.values()) {
            assertEquals(0.5, personality.normalized(trait));
            personality = personality.withTrait(trait, 80);
            assertEquals(++revision, personality.revision());
            assertEquals(0.8, personality.normalized(trait));
            assertThrows(IllegalArgumentException.class, () -> Personality.defaults().withTrait(trait, 101));
            assertThrows(IllegalArgumentException.class, () -> Personality.defaults().withTrait(trait, -1));
        }
        assertEquals(0.8, personality.normalized(Personality.Trait.SOCIABILITY));
        assertEquals(0.8, personality.normalized(Personality.Trait.MINING));
        assertTrue(Personality.Trait.FARMING.commandNames().contains("farmer"));
        assertTrue(Personality.Trait.HELPFULNESS.commandNames().contains("assistful"));
    }
}
