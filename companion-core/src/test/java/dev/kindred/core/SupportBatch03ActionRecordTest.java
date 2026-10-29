package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the record a player keeps of each action: one whose outcome is unknown, for example
 * after a crash, holds up what depends on it until fresh evidence settles it and is never simply run again,
 * and a finished record can never change.
 */
class SupportBatch03ActionRecordTest {

    private ActionRecord intent(){return ActionRecord.prepare(UUID.randomUUID(),new ActionLease(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,2,3),"transfer","source");}
    @Test void uncertainActionsRequireFreshEvidenceAndNeverReplay() {
        var prepared=intent();var dispatched=prepared.dispatched("before");var uncertain=dispatched.uncertain("crash");
        assertTrue(uncertain.blocksDependents());assertThrows(IllegalStateException.class,()->uncertain.dispatched("replay"));
        assertThrows(IllegalArgumentException.class,()->uncertain.reconciled(true,null,"guess"));
        var verified=uncertain.reconciled(true,"actual destination","observed");assertFalse(verified.blocksDependents());assertEquals(3,verified.revision());
        assertEquals(prepared.actionId(),verified.actionId());assertEquals("before",verified.beforeEvidence());
    }
    @Test void terminalRecordsRejectEveryFurtherTransition() {
        var prepared=intent();for(var terminal:List.of(prepared.cancelled("stop"),prepared.failed(null,"not dispatched"),prepared.dispatched("before").verified("after","observed"))){
            for(var target:ActionRecord.State.values())assertFalse(ActionRecord.validTransition(terminal.state(),target));
            assertThrows(IllegalStateException.class,()->terminal.uncertain("late"));
        }
    }
    @Test void dispatchedFailureWithoutEvidenceStaysUncertain() {
        var d=intent().dispatched("before");assertThrows(IllegalStateException.class,()->d.failed(null,"timeout"));assertThrows(IllegalStateException.class,()->d.cancelled("timeout"));
        assertEquals(ActionRecord.State.UNCERTAIN,d.uncertain("unknown").state());
    }

}
