package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionSettlementTest {
    private static final String BEFORE = "a".repeat(64), OTHER = "b".repeat(64);

    private static ActionRecord uncertain(String kind, String preconditions, String before) {
        var lease = new ActionLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        return ActionRecord.prepare(UUID.randomUUID(), lease, kind, preconditions)
                .dispatched(before)
                .uncertain("Startup reconciliation: the process ended before this action's outcome was recorded");
    }

    private static ActionRecord dig(String before) {
        return uncertain("native_break", "minecraft:stone at -57, 52, -243", before);
    }

    private static String inventory(String hash) { return "native owned inventory slots=41; sha256=" + hash; }

    @Test
    void theBlockStillThereAndTheInventoryAsBeforeIsNotDone() {
        var decision = ActionSettlement.decide(dig(inventory(BEFORE)), "minecraft:stone", inventory(BEFORE));
        assertEquals(ActionSettlement.Verdict.NOT_DONE, decision.verdict());
        assertTrue(decision.evidence().contains("still there"), decision.evidence());
    }

    @Test
    void theBlockGoneAndTheInventoryAsBeforeIsDone() {
        var decision = ActionSettlement.decide(dig(inventory(BEFORE)), "minecraft:air", inventory(BEFORE));
        assertEquals(ActionSettlement.Verdict.DONE, decision.verdict());
        assertTrue(decision.evidence().contains("now minecraft:air, gone"), decision.evidence());
    }

    @Test
    void anyOtherInventoryIsLeftToTheOperatorWhateverTheBlock() {
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.decide(dig(inventory(BEFORE)), "minecraft:stone", inventory(OTHER)).verdict());
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.decide(dig(inventory(BEFORE)), "minecraft:air", inventory(OTHER)).verdict());
    }

    @Test
    void whatCannotBeReadIsLeftToTheOperator() {
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.decide(dig(inventory(BEFORE)), null, inventory(BEFORE)).verdict());
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.decide(dig(inventory(BEFORE)), "minecraft:stone", null).verdict());
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.decide(dig("no fingerprint"), "minecraft:stone", inventory(BEFORE)).verdict());
    }

    @Test
    void onlyABreakHasAnAutomaticRule() {
        var walk = uncertain("native_goto", "minecraft:stone at 1, 2, 3", inventory(BEFORE));
        var decision = ActionSettlement.decide(walk, "minecraft:stone", inventory(BEFORE));
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, decision.verdict());
        assertTrue(decision.evidence().startsWith("no automatic rule for native_goto"), decision.evidence());
        var oddText = uncertain("native_break", "stone somewhere", inventory(BEFORE));
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.decide(oddText, "minecraft:stone", inventory(BEFORE)).verdict());
    }

    @Test
    void anActionNotUncertainIsNeverSettled() {
        var lease = new ActionLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1);
        var dispatched = ActionRecord.prepare(UUID.randomUUID(), lease, "native_break", "minecraft:stone at 1, 2, 3").dispatched(inventory(BEFORE));
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.decide(dispatched, "minecraft:stone", inventory(BEFORE)).verdict());
        assertThrows(IllegalStateException.class, () -> ActionSettlement.settled(dispatched, false, "x", "console"));
    }

    @Test
    void settlingWritesTheOutcomeWithWhoAndWhatWasSeenAndKeepsTheIntent() {
        var action = dig(inventory(BEFORE));
        var done = ActionSettlement.settled(action, true, "the evidence", "console (operator)");
        assertEquals(ActionRecord.State.VERIFIED, done.state());
        assertEquals(ActionRecord.Outcome.SUCCESS, done.outcome());
        assertEquals(action.beforeEvidence(), done.beforeEvidence());
        assertEquals(action.preconditions(), done.preconditions());
        assertEquals(action.revision() + 1, done.revision());
        assertTrue(done.detail().startsWith("Settled done by console (operator) after a restart: the evidence"), done.detail());
        var notDone = ActionSettlement.settled(action, false, "x".repeat(5000), "auto");
        assertEquals(ActionRecord.State.FAILED, notDone.state());
        assertEquals(ActionRecord.MAX_TEXT, notDone.detail().length());
        assertTrue(ActionRecord.validTransition(action.state(), notDone.state()));
    }

    @Test
    void theTargetIsReadFromTheBreaksOwnPreconditions() {
        var target = ActionSettlement.target("native_break", "minecraft:deepslate at -449, -54, -296").orElseThrow();
        assertEquals(new ActionSettlement.Target("minecraft:deepslate", -449, -54, -296), target);
        assertTrue(ActionSettlement.target("native_place", "minecraft:deepslate at 1, 2, 3").isEmpty());
    }

    @Test
    void theBreaksRuleIsTheOneDecideApplies() {
        assertEquals(ActionSettlement.Verdict.NOT_DONE, ActionSettlement.breakRule(true, inventory(BEFORE), inventory(BEFORE)));
        assertEquals(ActionSettlement.Verdict.DONE, ActionSettlement.breakRule(false, inventory(BEFORE), inventory(BEFORE)));
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.breakRule(true, inventory(BEFORE), inventory(OTHER)));
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.breakRule(false, inventory(BEFORE), inventory(OTHER)));
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.breakRule(false, "no fingerprint", inventory(BEFORE)));
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.breakRule(false, inventory(BEFORE), null));
        for (boolean present : new boolean[] {true, false}) for (String now : new String[] {BEFORE, OTHER}) {
            assertEquals(ActionSettlement.breakRule(present, inventory(BEFORE), inventory(now)),
                    ActionSettlement.decide(dig(inventory(BEFORE)), present ? "minecraft:stone" : "minecraft:air", inventory(now)).verdict());
        }
    }

    @Test
    void thePillarsCellIsReadFromItsOwnPreconditionsAndDecideLeavesItToThePillarsRule() {
        String preconditions = "block=minecraft:cobblestone; pos=-12, 64, 7; setupRevision=3";
        assertEquals(new ActionSettlement.Target("minecraft:cobblestone", -12, 64, 7), ActionSettlement.pillarCell("native_pillar", preconditions).orElseThrow());
        assertTrue(ActionSettlement.pillarCell("native_break", preconditions).isEmpty());
        assertTrue(ActionSettlement.pillarCell("native_pillar", "minecraft:cobblestone at -12, 64, 7").isEmpty());
        assertTrue(ActionSettlement.target("native_pillar", preconditions).isEmpty());
        var step = uncertain("native_pillar", preconditions, inventory(BEFORE));
        assertEquals(ActionSettlement.Verdict.AMBIGUOUS, ActionSettlement.decide(step, "minecraft:air", inventory(BEFORE)).verdict());
    }
}
