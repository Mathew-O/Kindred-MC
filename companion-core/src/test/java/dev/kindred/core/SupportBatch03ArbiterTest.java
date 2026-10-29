package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the one controller of a player's body: after a hundred stops and restarts no old
 * order can act again, and no new order can take the body until the cancelled one has fully stopped.
 */
class SupportBatch03ArbiterTest {

    @Test void hundredsOfStopResumeCyclesNeverReviveStaleAuthority() {
        var nanos=new AtomicLong();var owner=UUID.randomUUID();var c=Companion.create(UUID.randomUUID(),owner,"fixture",UUID.randomUUID(),"minecraft:overworld");
        var adapter=new BodyAdapter(){public void start(ActionLease l){} public void cancel(ActionLease l){}public boolean isSettled(ActionLease l){return true;}};
        var a=new ActionArbiter(c,UUID.randomUUID(),adapter,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),nanos::get,Duration.ofMillis(500));
        var stale=new ArrayList<ActionLease>();var effects=new AtomicInteger();
        for(int i=0;i<100;i++){
            var current=a.acquire(Actor.player(owner),UUID.randomUUID(),UUID.randomUUID(),Duration.ofSeconds(1));
            for(var old:stale){assertFalse(a.executeIfCurrent(old,effects::incrementAndGet));assertFalse(a.complete(old));}
            assertTrue(a.isCurrent(current));a.stop(Actor.player(owner));assertFalse(a.isCurrent(current));stale.add(current);a.resume(Actor.player(owner));
        }
        assertEquals(0,effects.get());
    }
    @Test void cancellationBarrierRejectsReplacementUntilSettlement() {
        var settled=new AtomicBoolean(false);var owner=UUID.randomUUID();var c=Companion.create(UUID.randomUUID(),owner,"fixture",UUID.randomUUID(),"minecraft:overworld");
        var body=new BodyAdapter(){public void start(ActionLease l){}public void cancel(ActionLease l){}public boolean isSettled(ActionLease l){return settled.get();}};
        var a=new ActionArbiter(c,body);var old=a.acquire(Actor.player(owner),UUID.randomUUID(),UUID.randomUUID(),Duration.ofSeconds(5));a.cancel(old);
        assertThrows(ControlRejectedException.class,()->a.acquireAutonomous(UUID.randomUUID(),UUID.randomUUID(),Duration.ofSeconds(5)));
        settled.set(true);var next=a.acquireAutonomous(UUID.randomUUID(),UUID.randomUUID(),Duration.ofSeconds(5));
        assertFalse(a.cancel(old));assertTrue(a.isCurrent(next));
    }

}
