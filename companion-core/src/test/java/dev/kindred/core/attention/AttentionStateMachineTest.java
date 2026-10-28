package dev.kindred.core.attention;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks a player's alertness moving between routine, alert and fighting.
 */
class AttentionStateMachineTest {
    private final UUID session = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();

    @Test
    void immediateAttackAndDangerEscalation() {
        var sm = new AttentionStateMachine(session, player);

        // 1. Initial observation makes state machine active in ALERT
        var snap0 = sm.observe(new SignalFrame(0, 1, true, false, ThreatKnowledge.CLEAR, 0, null, false));
        assertEquals(AttentionState.ALERT, snap0.state());
        assertTrue(snap0.optionalWorkActive());

        // 2. 40 quiet ticks in ALERT -> RECOVERY
        var snap40 = sm.observe(new SignalFrame(40, 1, true, false, ThreatKnowledge.CLEAR, 40, null, false));
        assertEquals(AttentionState.RECOVERY, snap40.state());

        // 3. 40 quiet ticks in RECOVERY without recoveryNeeded -> ROUTINE
        var snap80 = sm.observe(new SignalFrame(80, 1, true, false, ThreatKnowledge.CLEAR, 80, null, false));
        assertEquals(AttentionState.ROUTINE, snap80.state());

        // 4. Immediate environmental danger in ROUTINE escalates immediately to ALERT
        var snapDanger = sm.observe(new SignalFrame(81, 1, true, true, ThreatKnowledge.CLEAR, 81, null, false));
        assertEquals(AttentionState.ALERT, snapDanger.state());

        // 5. Immediate attack in ALERT escalates immediately to COMBAT
        var snapAttack = sm.observe(new SignalFrame(82, 1, true, false, ThreatKnowledge.PRESENT, 82, 1L, false));
        assertEquals(AttentionState.COMBAT, snapAttack.state());

        // 6. Return to ROUTINE, then immediate attack escalates directly to COMBAT
        // Reset to Routine
        var sm2 = new AttentionStateMachine(session, player);
        sm2.observe(new SignalFrame(0, 1, true, false, ThreatKnowledge.CLEAR, 0, null, false));
        sm2.observe(new SignalFrame(40, 1, true, false, ThreatKnowledge.CLEAR, 40, null, false));
        sm2.observe(new SignalFrame(80, 1, true, false, ThreatKnowledge.CLEAR, 80, null, false));
        assertEquals(AttentionState.ROUTINE, sm2.state());

        var snapDirectCombat = sm2.observe(new SignalFrame(85, 1, true, false, ThreatKnowledge.PRESENT, 85, 10L, false));
        assertEquals(AttentionState.COMBAT, snapDirectCombat.state());
    }

    @Test
    void quietHysteresisAndUnknownThreatFallback() {
        var sm = new AttentionStateMachine(session, player);
        // Start in Combat via attack at tick 0
        sm.observe(new SignalFrame(0, 1, true, false, ThreatKnowledge.PRESENT, 0, 1L, false));
        assertEquals(AttentionState.COMBAT, sm.state());

        // At tick 59 (less than 60 ticks quiet), must remain in Combat even if CLEAR
        var snap59 = sm.observe(new SignalFrame(59, 1, true, false, ThreatKnowledge.CLEAR, 59, null, false));
        assertEquals(AttentionState.COMBAT, snap59.state());

        // At tick 60, if threat is CLEAR, transitions to RECOVERY
        var snap60 = sm.observe(new SignalFrame(60, 1, true, false, ThreatKnowledge.CLEAR, 60, null, false));
        assertEquals(AttentionState.RECOVERY, snap60.state());

        // Test UNKNOWN fallback: after 60 ticks of quiet, UNKNOWN threat falls back to ALERT, NOT Recovery!
        var smUnknown = new AttentionStateMachine(session, player);
        smUnknown.observe(new SignalFrame(0, 1, true, false, ThreatKnowledge.PRESENT, 0, 2L, false));
        assertEquals(AttentionState.COMBAT, smUnknown.state());

        // At tick 60, threat is UNKNOWN
        var snapFallback = smUnknown.observe(new SignalFrame(60, 1, true, false, ThreatKnowledge.UNKNOWN, 60, null, false));
        assertEquals(AttentionState.ALERT, snapFallback.state());

        // Test stale observation: threat observed at tick 45 is 16 ticks old at tick 61 (> 10 max freshness)
        var smStale = new AttentionStateMachine(session, player);
        smStale.observe(new SignalFrame(0, 1, true, false, ThreatKnowledge.PRESENT, 0, 3L, false));
        var snapStale = smStale.observe(new SignalFrame(60, 1, true, false, ThreatKnowledge.CLEAR, 45, null, false));
        assertEquals(AttentionState.ALERT, snapStale.state(), "Stale CLEAR threat must evaluate as UNKNOWN and fall back to Alert");
    }

    @Test
    void attackEventDeduplication() {
        var sm = new AttentionStateMachine(session, player);
        // Initial attack with sequence 1 at tick 10
        sm.observe(new SignalFrame(10, 1, true, false, ThreatKnowledge.PRESENT, 10, 1L, false));
        assertEquals(AttentionState.COMBAT, sm.state());

        // Repeated delivery of sequence 1 does NOT update encounter time
        for (long t = 11; t <= 69; t++) {
            sm.observe(new SignalFrame(t, 1, true, false, ThreatKnowledge.CLEAR, t, 1L, false));
            assertEquals(AttentionState.COMBAT, sm.state());
        }

        // At tick 70 (60 ticks since attack 1), it must transition to RECOVERY
        var snap70 = sm.observe(new SignalFrame(70, 1, true, false, ThreatKnowledge.CLEAR, 70, 1L, false));
        assertEquals(AttentionState.RECOVERY, snap70.state(), "Duplicate attack sequence must not extend combat indefinitely");

        // Higher sequence 2 immediately re-escalates to Combat
        var snapNew = sm.observe(new SignalFrame(71, 1, true, false, ThreatKnowledge.PRESENT, 71, 2L, false));
        assertEquals(AttentionState.COMBAT, snapNew.state());
    }

    @Test
    void recoveryNeedHoldsRecoveryAndDangerPreempts() {
        var sm = new AttentionStateMachine(session, player);
        // Start in Alert, advance 40 ticks -> Recovery
        sm.observe(new SignalFrame(0, 1, true, false, ThreatKnowledge.CLEAR, 0, null, false));
        sm.observe(new SignalFrame(40, 1, true, false, ThreatKnowledge.CLEAR, 40, null, true));
        assertEquals(AttentionState.RECOVERY, sm.state());

        // In Recovery, 50 more ticks elapse but recoveryNeeded is true
        var snap90 = sm.observe(new SignalFrame(90, 1, true, false, ThreatKnowledge.CLEAR, 90, null, true));
        assertEquals(AttentionState.RECOVERY, snap90.state(), "Recovery need must hold Recovery state");

        // When danger arrives, it immediately wins over recovery need
        var snapDanger = sm.observe(new SignalFrame(91, 1, true, true, ThreatKnowledge.CLEAR, 91, null, true));
        assertEquals(AttentionState.ALERT, snapDanger.state(), "Danger must immediately preempt Recovery");
    }

    @Test
    void stopFaultAndBodyGenerationFencing() {
        var sm = new AttentionStateMachine(session, player);
        var s1 = sm.observe(new SignalFrame(0, 1, true, false, ThreatKnowledge.CLEAR, 0, null, false));
        assertTrue(s1.optionalWorkActive());
        assertEquals(1, s1.bodyGeneration());

        // Inactive / unoperational operational=false
        var s2 = sm.observe(new SignalFrame(1, 1, false, false, ThreatKnowledge.CLEAR, 1, null, false));
        assertFalse(s2.optionalWorkActive(), "Stopped/unoperational body must deactivate optional work");
        assertTrue(s2.revision() > s1.revision());

        // Body generation replacement
        var s3 = sm.observe(new SignalFrame(2, 2, true, false, ThreatKnowledge.CLEAR, 2, null, false));
        assertTrue(s3.optionalWorkActive());
        assertEquals(2, s3.bodyGeneration());
        assertEquals(AttentionState.ALERT, s3.state(), "New body generation must start in Alert");
    }

    @Test
    void backwardTickWithinSessionIsRejected() {
        var sm = new AttentionStateMachine(session, player);
        sm.observe(new SignalFrame(10, 1, true, false, ThreatKnowledge.CLEAR, 10, null, false));
        assertThrows(IllegalArgumentException.class, () ->
                sm.observe(new SignalFrame(9, 1, true, false, ThreatKnowledge.CLEAR, 9, null, false)));
    }
}
