package dev.kindred.persistence;
import dev.kindred.core.*;
import dev.kindred.scheduler.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that the saved record of actions and of play time survives the server stopping: an
 * action whose outcome was unknown is still waiting to be settled when the database reopens, and time that
 * was booked but never settled is charged exactly once.
 */
class SupportBatch02LedgerTest {
    @TempDir Path dir;
    @Test void uncertainLedgerReopenRetainsIntentFencesAndRequiresReconciliation() {
        Path db=dir.resolve("ledger.sqlite");ActionRecord uncertain;UUID id;
        var store=SqliteCompanionStore.open(db,3).join();try {
            id=store.create(new CreationRequest(UUID.randomUUID(),UUID.randomUUID(),"fixture",UUID.randomUUID(),"minecraft:overworld")).join().companion().id();
            var lease=new ActionLease(id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,2,3);
            var prepared=ActionRecord.prepare(UUID.randomUUID(),lease,"transfer","observed source");store.prepareAction(prepared).join();
            var dispatched=prepared.dispatched("source before");store.transitionAction(dispatched,0).join();uncertain=dispatched.uncertain("crash after dispatch");store.transitionAction(uncertain,1).join();
        } finally {store.closeAsync().join();}
        store=SqliteCompanionStore.open(db,3).join();try {
            assertEquals(List.of(uncertain),store.unresolvedActions(id).join());assertEquals(uncertain,store.findAction(uncertain.actionId()).join().orElseThrow());
            var verified=uncertain.reconciled(true,"destination observed","operator reconciliation");assertEquals(verified,store.transitionAction(verified,2).join());
            assertTrue(store.unresolvedActions(id).join().isEmpty());
        }finally{store.closeAsync().join();}
    }
    @Test void outstandingBudgetSurvivesReopenAndIsChargedExactlyOnce() {
        Path db=dir.resolve("budget.sqlite");UUID id;var period=AccountingPeriod.on(LocalDate.of(2026,10,24),ZoneOffset.UTC);BudgetState reserved;
        var store=SqliteCompanionStore.open(db,3).join();try {
            id=store.create(new CreationRequest(UUID.randomUUID(),UUID.randomUUID(),"fixture",UUID.randomUUID(),"minecraft:overworld")).join().companion().id();
            var empty=store.openBudget(id,period).join();reserved=empty.reserve(UUID.randomUUID(),Duration.ofSeconds(5),Duration.ofSeconds(10),period.startInclusive(),period.endExclusive());store.saveBudget(reserved,empty.sequence()).join();
        }finally{store.closeAsync().join();}
        store=SqliteCompanionStore.open(db,3).join();try {
            var reopened=store.openBudget(id,period).join();assertEquals(5000,reopened.chargedMillis());assertTrue(reopened.outstanding().isEmpty());assertEquals(0,reopened.observedActiveMillis());
            assertEquals(reopened,store.openBudget(id,period).join());assertEquals(Duration.ofSeconds(5),reopened.remainingAllowance(Duration.ofSeconds(10)));
        }finally{store.closeAsync().join();}
    }
}
