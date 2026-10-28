package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks what catches a player's eye and for how long, and that danger always comes first.
 */
class AttentionPolicyTest {
    private static final Personality NEUTRAL = Personality.defaults();
    private static final Set<Mannerisms.Quirk> NO_QUIRKS = Set.of();
    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();

    @Test
    void noticesOnlyNewContactsDuringInterruptiblePhasesAndReturnsNothingOtherwise() {
        AttentionPolicy policy = new AttentionPolicy();
        assertTrue(policy.observe(Set.of(), AttentionPolicy.Phase.BROWSING_CONTAINER, true, false, NEUTRAL, NO_QUIRKS, 0).isEmpty());
        var glance = policy.observe(Set.of(alex), AttentionPolicy.Phase.BROWSING_CONTAINER, true, false, NEUTRAL, NO_QUIRKS, 1_000).orElseThrow();
        assertEquals(alex, glance.subject());
        assertTrue(glance.durationMillis() >= AttentionPolicy.MIN_GLANCE_MILLIS && glance.durationMillis() <= AttentionPolicy.MAX_GLANCE_MILLIS);
        assertTrue(glance.remark());
        // Someone who stays is not re-noticed, even after every cooldown has passed.
        assertTrue(policy.observe(Set.of(alex), AttentionPolicy.Phase.BROWSING_CONTAINER, true, false, NEUTRAL, NO_QUIRKS, 600_000).isEmpty());
        // Leaving and returning after the per-contact cooldown counts as new.
        assertTrue(policy.observe(Set.of(), AttentionPolicy.Phase.BROWSING_CONTAINER, true, false, NEUTRAL, NO_QUIRKS, 601_000).isEmpty());
        assertTrue(policy.observe(Set.of(alex), AttentionPolicy.Phase.BROWSING_CONTAINER, true, false, NEUTRAL, NO_QUIRKS, 602_000).isPresent());
        for (var committed : new AttentionPolicy.Phase[]{AttentionPolicy.Phase.BREAKING, AttentionPolicy.Phase.TRANSFERRING,
                AttentionPolicy.Phase.PLACING, AttentionPolicy.Phase.AIRBORNE, AttentionPolicy.Phase.CANCELLING}) {
            AttentionPolicy busy = new AttentionPolicy();
            assertTrue(busy.observe(Set.of(sam), committed, true, false, NEUTRAL, NO_QUIRKS, 0).isEmpty(), committed.name());
            assertFalse(committed.interruptible());
        }
    }

    @Test
    void cooldownsAndBudgetBoundTheInterruptions() {
        AttentionPolicy policy = new AttentionPolicy();
        assertTrue(policy.observe(Set.of(alex), AttentionPolicy.Phase.IDLE, true, false, NEUTRAL, NO_QUIRKS, 0).isPresent());
        // A second newcomer inside the global cooldown waits; the same person inside two minutes is skipped.
        assertTrue(policy.observe(Set.of(alex, sam), AttentionPolicy.Phase.IDLE, true, false, NEUTRAL, NO_QUIRKS, 10_000).isEmpty());
        assertTrue(policy.observe(Set.of(alex), AttentionPolicy.Phase.IDLE, true, false, NEUTRAL, NO_QUIRKS, 25_000).isEmpty());
        assertTrue(policy.observe(Set.of(), AttentionPolicy.Phase.IDLE, true, false, NEUTRAL, NO_QUIRKS, 30_000).isEmpty());
        assertTrue(policy.observe(Set.of(alex), AttentionPolicy.Phase.IDLE, true, false, NEUTRAL, NO_QUIRKS, 31_000).isEmpty(), "same person within two minutes");
        assertTrue(policy.observe(Set.of(alex, sam), AttentionPolicy.Phase.IDLE, true, false, NEUTRAL, NO_QUIRKS, 32_000).isPresent(), "sam is new and the global cooldown passed");

        // The per-minute budget: a maximally curious companion spends 2.5 s per glance, so a third glance in a minute is refused.
        Personality curious = NEUTRAL.withTrait(Personality.Trait.CURIOSITY, 100).withTrait(Personality.Trait.CAUTION, 0);
        AttentionPolicy budget = new AttentionPolicy();
        long now = 0;
        int admitted = 0;
        for (int i = 0; i < 3; i++, now += AttentionPolicy.GLOBAL_COOLDOWN_MILLIS) {
            if (budget.observe(Set.of(UUID.randomUUID()), AttentionPolicy.Phase.WAITING, true, false, curious, NO_QUIRKS, now).isPresent()) admitted++;
        }
        assertEquals(2, admitted);
    }

    @Test
    void aNewcomerDuringCommittedWorkIsNoticedAtTheNextCheckpointOrDroppedWhenTooLate() {
        AttentionPolicy policy = new AttentionPolicy();
        assertTrue(policy.observe(Set.of(alex), AttentionPolicy.Phase.BREAKING, true, false, NEUTRAL, NO_QUIRKS, 0).isEmpty());
        assertTrue(policy.observe(Set.of(alex), AttentionPolicy.Phase.BREAKING, true, false, NEUTRAL, NO_QUIRKS, 5_000).isEmpty());
        assertEquals(alex, policy.observe(Set.of(alex), AttentionPolicy.Phase.IDLE, true, false, NEUTRAL, NO_QUIRKS, 6_000).orElseThrow().subject());
        AttentionPolicy slow = new AttentionPolicy();
        assertTrue(slow.observe(Set.of(sam), AttentionPolicy.Phase.TRANSFERRING, true, false, NEUTRAL, NO_QUIRKS, 0).isEmpty());
        assertTrue(slow.observe(Set.of(sam), AttentionPolicy.Phase.IDLE, true, false, NEUTRAL, NO_QUIRKS, AttentionPolicy.PENDING_LIMIT_MILLIS + 1).isEmpty(),
                "looking up half a minute later would not read as noticing someone arrive");
    }

    @Test
    void gazeOwnershipAndLeaseLossReduceOrRemoveThePause() {
        AttentionPolicy policy = new AttentionPolicy();
        var nod = policy.observe(Set.of(alex), AttentionPolicy.Phase.WALKING_UNCOMMITTED, true, true, NEUTRAL, NO_QUIRKS, 0).orElseThrow();
        assertEquals(0, nod.durationMillis(), "a skill owning the gaze leaves only a head-free acknowledgement");
        assertTrue(nod.remark());
        AttentionPolicy stopped = new AttentionPolicy();
        assertTrue(stopped.observe(Set.of(alex), AttentionPolicy.Phase.IDLE, false, false, NEUTRAL, NO_QUIRKS, 0).isEmpty());
        var quiet = new AttentionPolicy().observe(Set.of(alex), AttentionPolicy.Phase.IDLE, true, false,
                NEUTRAL.withTrait(Personality.Trait.SOCIABILITY, 0), NO_QUIRKS, 0).orElseThrow();
        assertFalse(quiet.remark(), "sociability zero still glances but never speaks");
    }

    @Test
    void glanceLengthFollowsCuriosityCautionAndTheCuriousGlanceQuirkWithinBounds() {
        long neutral = AttentionPolicy.duration(NEUTRAL, NO_QUIRKS);
        long curious = AttentionPolicy.duration(NEUTRAL.withTrait(Personality.Trait.CURIOSITY, 100), NO_QUIRKS);
        long cautious = AttentionPolicy.duration(NEUTRAL.withTrait(Personality.Trait.CAUTION, 100), NO_QUIRKS);
        long quirky = AttentionPolicy.duration(NEUTRAL, Set.of(Mannerisms.Quirk.CURIOUSGLANCE));
        assertTrue(curious > neutral && neutral > cautious);
        assertTrue(quirky > neutral);
        assertTrue(cautious >= AttentionPolicy.MIN_GLANCE_MILLIS);
        assertTrue(AttentionPolicy.duration(NEUTRAL.withTrait(Personality.Trait.CURIOSITY, 100), Set.of(Mannerisms.Quirk.CURIOUSGLANCE))
                <= AttentionPolicy.MAX_GLANCE_MILLIS);
    }
}
