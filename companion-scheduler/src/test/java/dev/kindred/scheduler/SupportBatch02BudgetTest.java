package dev.kindred.scheduler;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the play-time budget for one day as time is booked and settled: over many slices,
 * and after a crash, no time is lost or invented, a wrong or second booking cannot spend or refund anything,
 * a booking never runs past the end of the day or the cap, and a crash is charged only once.
 */
class SupportBatch02BudgetTest {

    private final Instant now=Instant.parse("2026-10-24T12:00:00Z");
    private BudgetState empty(){return BudgetState.empty(UUID.randomUUID(),AccountingPeriod.containing(now,ZoneOffset.UTC));}
    @Test void settleAndCrashRecoveryConserveAllowanceOverManySlices() {
        BudgetState b=empty();long charged=0,observed=0;Duration cap=Duration.ofSeconds(60);
        for(int i=0;i<12;i++) {
            var id=UUID.randomUUID();b=b.reserve(id,Duration.ofSeconds(5),cap,now,now.plusSeconds(60));
            long reserved=b.outstanding().orElseThrow().reservedMillis();
            assertEquals(60000,charged+reserved+b.remainingAllowance(cap).toMillis());
            if(i%3==0){b=b.recoverAfterCrash();charged+=reserved;}
            else {b=b.settle(id,Duration.ofMillis(1234));charged+=1234;observed+=1234;}
            assertEquals(charged,b.chargedMillis());assertEquals(observed,b.observedActiveMillis());
        }
    }
    @Test void staleIdentityAndDuplicateReservationCannotSpendOrRefund() {
        var id=UUID.randomUUID();var b=empty().reserve(id,Duration.ofSeconds(1),Duration.ofSeconds(10),now,now.plusSeconds(5));
        assertThrows(IllegalStateException.class,()->b.settle(UUID.randomUUID(),Duration.ZERO));
        assertThrows(IllegalStateException.class,()->b.reserve(UUID.randomUUID(),Duration.ofSeconds(1),Duration.ofSeconds(10),now,now.plusSeconds(5)));
        assertEquals(1000,b.outstanding().orElseThrow().reservedMillis());
    }
    @Test void deadlineAndCapClipReservationsWithoutCrossingDay() {
        var b=empty();Instant end=b.period().endExclusive();
        b=b.reserve(UUID.randomUUID(),Duration.ofSeconds(5),Duration.ofMillis(300),end.minusMillis(250),end.plusSeconds(10));
        assertEquals(250,b.outstanding().orElseThrow().reservedMillis());assertEquals(end,b.outstanding().orElseThrow().expiresAt());
    }
    @Test void crashChargeIsIdempotentWithoutInventingActiveTime() {
        var b=empty().reserve(UUID.randomUUID(),Duration.ofSeconds(5),Duration.ofSeconds(10),now,now.plusSeconds(5)).recoverAfterCrash();
        assertSame(b,b.recoverAfterCrash());assertEquals(5000,b.chargedMillis());assertEquals(0,b.observedActiveMillis());
    }

}
