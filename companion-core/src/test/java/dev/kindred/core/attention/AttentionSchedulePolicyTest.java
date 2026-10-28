package dev.kindred.core.attention;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how often each kind of thinking runs so a player stays quick to react.
 */
class AttentionSchedulePolicyTest {

    @Test
    void defaultPolicyCadencesAndEligibility() {
        var policy = AttentionSchedulePolicy.defaultPolicy();
        assertEquals(6, policy.maxSlicesPerTick());
        assertEquals(2, policy.maxDetailedScansPerTick());
        assertEquals(2_000_000L, policy.softTimeLimitNanos());
        assertEquals(3, policy.maxPlayers());
        assertFalse(policy.learningReviewEnabled());

        // Routine
        assertEquals(5, policy.cadenceTicks(AttentionState.ROUTINE, WorkKind.BROAD_THREAT));
        assertEquals(20, policy.cadenceTicks(AttentionState.ROUTINE, WorkKind.EQUIPMENT_READINESS));
        assertEquals(5, policy.cadenceTicks(AttentionState.ROUTINE, WorkKind.ROUTINE_GOALS));
        assertEquals(10, policy.cadenceTicks(AttentionState.ROUTINE, WorkKind.RESOURCE_DISCOVERY));
        assertEquals(40, policy.cadenceTicks(AttentionState.ROUTINE, WorkKind.SOCIAL_DELIBERATION));
        assertEquals(-1, policy.cadenceTicks(AttentionState.ROUTINE, WorkKind.LEARNING_REVIEW), "Disabled by default");

        assertTrue(policy.isEligible(AttentionState.ROUTINE, WorkKind.ROUTINE_GOALS));
        assertFalse(policy.isEligible(AttentionState.ROUTINE, WorkKind.LEARNING_REVIEW));

        // Alert
        assertEquals(2, policy.cadenceTicks(AttentionState.ALERT, WorkKind.BROAD_THREAT));
        assertEquals(10, policy.cadenceTicks(AttentionState.ALERT, WorkKind.EQUIPMENT_READINESS));
        assertEquals(-1, policy.cadenceTicks(AttentionState.ALERT, WorkKind.ROUTINE_GOALS));
        assertEquals(-1, policy.cadenceTicks(AttentionState.ALERT, WorkKind.RESOURCE_DISCOVERY));
        assertEquals(-1, policy.cadenceTicks(AttentionState.ALERT, WorkKind.SOCIAL_DELIBERATION));

        assertFalse(policy.isEligible(AttentionState.ALERT, WorkKind.ROUTINE_GOALS));
        assertFalse(policy.isEligible(AttentionState.ALERT, WorkKind.RESOURCE_DISCOVERY));

        // Combat
        assertEquals(2, policy.cadenceTicks(AttentionState.COMBAT, WorkKind.BROAD_THREAT));
        assertEquals(10, policy.cadenceTicks(AttentionState.COMBAT, WorkKind.EQUIPMENT_READINESS));
        assertEquals(-1, policy.cadenceTicks(AttentionState.COMBAT, WorkKind.ROUTINE_GOALS));

        // Recovery
        assertEquals(5, policy.cadenceTicks(AttentionState.RECOVERY, WorkKind.BROAD_THREAT));
        assertEquals(5, policy.cadenceTicks(AttentionState.RECOVERY, WorkKind.EQUIPMENT_READINESS));
        assertEquals(10, policy.cadenceTicks(AttentionState.RECOVERY, WorkKind.ROUTINE_GOALS));
        assertEquals(20, policy.cadenceTicks(AttentionState.RECOVERY, WorkKind.RESOURCE_DISCOVERY));
        assertEquals(-1, policy.cadenceTicks(AttentionState.RECOVERY, WorkKind.SOCIAL_DELIBERATION));

        assertTrue(policy.isEligible(AttentionState.RECOVERY, WorkKind.ROUTINE_GOALS));
    }
}
