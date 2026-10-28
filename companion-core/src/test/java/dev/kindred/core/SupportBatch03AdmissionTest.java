package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch03AdmissionTest {

    private ActionRecord intent(){return ActionRecord.prepare(UUID.randomUUID(),new ActionLease(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,2,3),"transfer","observed source");}
    @Test void eachAcknowledgementMustBeOwnerDispatchedBeforeSingleUseAdmission() {
        var queue=new ArrayDeque<Runnable>();var prepared=new CompletableFuture<ActionRecord>();var dispatched=new CompletableFuture<ActionRecord>();
        var record=intent();var a=new ActionAdmission(record,"before",new ActionAdmission.Writer(){public CompletableFuture<ActionRecord> prepare(ActionRecord r){return prepared;}public CompletableFuture<ActionRecord> transition(ActionRecord n,long rev){assertEquals(0,rev);return dispatched;}},queue::add);
        prepared.complete(record);assertTrue(a.take().isEmpty());queue.remove().run();assertTrue(a.take().isEmpty());
        dispatched.complete(record.dispatched("before"));assertTrue(a.take().isEmpty());queue.remove().run();assertEquals(ActionRecord.State.DISPATCHED,a.take().orElseThrow().state());assertTrue(a.take().isEmpty());
    }
    @Test void mismatchedPreparedAcknowledgementBlocksWithoutDispatching() {
        var record=intent();var transitions=new AtomicInteger();
        var a=new ActionAdmission(record,"before",new ActionAdmission.Writer(){public CompletableFuture<ActionRecord> prepare(ActionRecord r){return CompletableFuture.completedFuture(intent());}public CompletableFuture<ActionRecord> transition(ActionRecord n,long rev){transitions.incrementAndGet();return CompletableFuture.completedFuture(n);}},Runnable::run);
        assertEquals(ActionAdmission.Phase.BLOCKED,a.phase());assertTrue(a.take().isEmpty());assertEquals(0,transitions.get());
    }

}
