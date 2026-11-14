package dev.kindred.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItemSourceLedgerTest {
    private static final UUID ADA = new UUID(0, 1);
    private static final UUID BRAM = new UUID(0, 2);
    private static final String LOG = "minecraft:oak_log";
    private static final String DIAMOND = "minecraft:diamond";

    @Test
    void aRisePaidByASourceInItsWindowIsClean() {
        var ledger = new ItemSourceLedger();
        ledger.observe(ADA, "Ada", Map.of(), 0);
        ledger.endWindow();
        ledger.credit(ADA, LOG, 3, "pickup:Block");
        ledger.observe(ADA, "Ada", Map.of(LOG, 3), 20);
        ledger.endWindow();
        ledger.endWindow();
        var report = ledger.report();
        assertTrue(report.clean(), report.violations().toString());
        assertEquals(3, report.gained());
        assertEquals(3, report.paid());
        assertEquals(Map.of("pickup:Block", 3L), report.bySource());
    }

    @Test
    void aRiseNoSourcePaysIsAViolationOnceItsCarryIsOver() {
        var ledger = new ItemSourceLedger();
        ledger.observe(ADA, "Ada", Map.of(), 0);
        ledger.endWindow();
        ledger.credit(ADA, LOG, 1, "pickup:Block");
        ledger.observe(ADA, "Ada", Map.of(LOG, 1, DIAMOND, 1), 20);
        ledger.endWindow();
        assertTrue(ledger.report().clean(), "Not yet: a source may still be reported in the next window");
        ledger.endWindow();
        var report = ledger.report();
        assertEquals(1, report.violationCount());
        assertTrue(report.violations().getFirst().contains("Ada gained 1 minecraft:diamond with no source"), report.violations().toString());
    }

    @Test
    void aSourceReportedInOneWindowPaysTheRiseSeenInTheNext() {
        var ledger = new ItemSourceLedger();
        ledger.observe(ADA, "Ada", Map.of(), 0);
        ledger.credit(ADA, LOG, 2, "screen:CraftingScreenHandler");
        ledger.endWindow();
        ledger.observe(ADA, "Ada", Map.of(LOG, 2), 20);
        ledger.endWindow();
        ledger.endWindow();
        var report = ledger.report();
        assertTrue(report.clean(), report.violations().toString());
        assertEquals(0, report.lapsed());
    }

    @Test
    void aRiseSeenInOneWindowIsPaidByASourceReportedInTheNext() {
        var ledger = new ItemSourceLedger();
        ledger.observe(ADA, "Ada", Map.of(), 0);
        ledger.endWindow();
        ledger.observe(ADA, "Ada", Map.of(LOG, 2), 20);
        ledger.endWindow();
        ledger.credit(ADA, LOG, 2, "market.purchase");
        ledger.endWindow();
        var report = ledger.report();
        assertTrue(report.clean(), report.violations().toString());
        assertEquals(2, report.paid());
    }

    @Test
    void aCreditCarriedTooLongLapsesAndPaysNothingLater() {
        var ledger = new ItemSourceLedger();
        ledger.observe(ADA, "Ada", Map.of(), 0);
        ledger.credit(ADA, DIAMOND, 1, "pickup:Block");
        ledger.endWindow();
        ledger.endWindow();
        ledger.observe(ADA, "Ada", Map.of(DIAMOND, 1), 40);
        ledger.endWindow();
        ledger.endWindow();
        var report = ledger.report();
        assertEquals(1, report.lapsed());
        assertEquals(1, report.violationCount(), report.violations().toString());
    }

    @Test
    void creditsAreThePlayersOwnAndTheItemsOwn() {
        var ledger = new ItemSourceLedger();
        ledger.observe(ADA, "Ada", Map.of(), 0);
        ledger.observe(BRAM, "Bram", Map.of(), 0);
        ledger.endWindow();
        ledger.credit(BRAM, DIAMOND, 1, "pickup:Block");
        ledger.credit(ADA, LOG, 1, "pickup:Block");
        ledger.observe(ADA, "Ada", Map.of(DIAMOND, 1), 20);
        ledger.endWindow();
        ledger.endWindow();
        var report = ledger.report();
        assertEquals(1, report.violationCount(), "Bram's diamond and Ada's log pay nothing for Ada's diamond: " + report.violations());
    }

    @Test
    void goingIsNeverQuestionedAndARiseBackAfterItIsOnlyTheRiseOverTheLastCount() {
        var ledger = new ItemSourceLedger();
        ledger.observe(ADA, "Ada", Map.of(), 0);
        ledger.endWindow();
        ledger.credit(ADA, LOG, 4, "pickup:Block");
        ledger.observe(ADA, "Ada", Map.of(LOG, 4), 20);
        ledger.endWindow();
        ledger.observe(ADA, "Ada", Map.of(LOG, 1), 40);
        ledger.endWindow();
        ledger.credit(ADA, LOG, 2, "pickup:PlayerEntity");
        ledger.observe(ADA, "Ada", Map.of(LOG, 3), 60);
        ledger.endWindow();
        ledger.endWindow();
        var report = ledger.report();
        assertTrue(report.clean(), report.violations().toString());
        assertEquals(6, report.gained());
    }

    @Test
    void aPlayerFirstSeenHoldingItemsIsAViolation() {
        var ledger = new ItemSourceLedger();
        ledger.observe(ADA, "Ada", Map.of(DIAMOND, 2), 7);
        var report = ledger.report();
        assertEquals(1, report.violationCount());
        assertTrue(report.violations().getFirst().contains("Ada started with {minecraft:diamond=2}"), report.violations().toString());
    }

    @Test
    void aFixturesStagedItemsAreItsBaseline() {
        var ledger = new ItemSourceLedger(false);
        ledger.observe(ADA, "Ada", Map.of(DIAMOND, 2), 7);
        ledger.endWindow();
        ledger.observe(ADA, "Ada", Map.of(DIAMOND, 3), 27);
        ledger.endWindow();
        ledger.endWindow();
        var report = ledger.report();
        assertEquals(1, report.violationCount(), "Only the third diamond: " + report.violations());
        assertEquals(1, report.gained());
    }
}
