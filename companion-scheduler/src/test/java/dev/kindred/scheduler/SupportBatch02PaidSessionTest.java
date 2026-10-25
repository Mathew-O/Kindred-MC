package dev.kindred.scheduler;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch02PaidSessionTest {

    private final Instant now=Instant.parse("2026-10-24T12:00:00Z");
    private final ArrayDeque<Runnable> callbacks=new ArrayDeque<>();
    private final ArrayList<BudgetState> writes=new ArrayList<>();
    private final ArrayList<CompletableFuture<BudgetState>> acknowledgements=new ArrayList<>();
    private PaidActivitySession session(){
        return new PaidActivitySession(BudgetState.empty(UUID.randomUUID(),AccountingPeriod.containing(now,ZoneOffset.UTC)),Duration.ofSeconds(20),
            (next,seq)->{assertEquals(next.sequence()-1,seq);writes.add(next);var f=new CompletableFuture<BudgetState>();acknowledgements.add(f);return f;},
            callbacks::add,Clock.fixed(now,ZoneOffset.UTC),()->0L);
    }
    private void pump(){while(!callbacks.isEmpty())callbacks.remove().run();}
    @Test void stopBeforeIoAndLateOwnerCallbackCannotActivate() {
        var s=session();var r=s.request(now.plusSeconds(20));s.stop();assertFalse(r.join());
        acknowledgements.get(0).complete(writes.get(0));assertFalse(s.paid());pump();assertFalse(s.paid());
        acknowledgements.get(1).complete(writes.get(1));pump();assertEquals(PaidActivitySession.Phase.IDLE,s.phase());
        assertEquals(0,s.snapshot().chargedMillis());assertTrue(s.snapshot().outstanding().isEmpty());
    }
    @Test void closeDuringReservationFlushesWithoutOwnerCallbacks() {
        var s=session();var r=s.request(now.plusSeconds(20));var closed=s.close();assertSame(closed,s.close());
        acknowledgements.get(0).complete(writes.get(0));acknowledgements.get(1).complete(writes.get(1));
        assertFalse(r.join());assertEquals(0,closed.join().chargedMillis());assertTrue(closed.join().outstanding().isEmpty());
        pump();assertEquals(PaidActivitySession.Phase.CLOSED,s.phase());assertFalse(s.paid());
    }
    @Test void mismatchedAcknowledgementBlocksInsteadOfRetrying() {
        var s=session();var r=s.request(now.plusSeconds(20));
        acknowledgements.get(0).complete(BudgetState.empty(UUID.randomUUID(),writes.get(0).period()));pump();
        assertTrue(r.isCompletedExceptionally());assertEquals(PaidActivitySession.Phase.BLOCKED,s.phase());
        assertFalse(s.request(now.plusSeconds(20)).join());assertEquals(1,writes.size());
    }

}
