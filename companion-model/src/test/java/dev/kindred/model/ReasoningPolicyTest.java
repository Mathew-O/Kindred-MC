package dev.kindred.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.kindred.model.ReasoningPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks when a player may ask an AI model for advice, and that it never asks on an ordinary
 * tick.
 */
class ReasoningPolicyTest {
    private final UUID companion = UUID.randomUUID();
    private final UUID epoch = UUID.randomUUID();
    private final Context ready = new Context(true, true, false, true, true, true);

    private Key key(Trigger trigger, long observations) {
        return new Key(companion, trigger == Trigger.DIRECT_CHAT ? ModelGateway.Lane.CHAT : ModelGateway.Lane.PLANNER,
                epoch, 1, 2, observations, trigger);
    }

    @Test void ticksAndIdenticalDemandsNeverSpendTokens() {
        assertEquals(Verdict.NO_CHANGE, evaluate(key(Trigger.ROUTINE_TICK, 1), null, ready));
        Key demand = key(Trigger.NEW_GOAL, 1);
        assertEquals(Verdict.REQUEST, evaluate(demand, null, ready));
        assertEquals(Verdict.NO_CHANGE, evaluate(demand, demand, ready));
        assertEquals(Verdict.REQUEST, evaluate(key(Trigger.PLAN_INVALIDATED, 2), demand, ready));
        assertEquals(Verdict.REQUEST, evaluate(key(Trigger.LOCAL_RECOVERY_EXHAUSTED, 3), demand, ready));
    }

    @Test void providerBudgetInflightAndActivityAreIndependentGates() {
        Key demand = key(Trigger.NEW_GOAL, 1);
        assertEquals(Verdict.DISABLED, evaluate(demand, null, new Context(false, true, false, true, true, true)));
        assertEquals(Verdict.BUDGET_EXHAUSTED, evaluate(demand, null, new Context(true, false, false, true, true, true)));
        assertEquals(Verdict.IN_FLIGHT, evaluate(demand, null, new Context(true, true, true, true, true, true)));
        assertEquals(Verdict.NOT_PERMITTED, evaluate(demand, null, new Context(true, true, false, false, true, true)));
        assertEquals(Verdict.NOT_PERMITTED, evaluate(demand, null, new Context(true, true, false, true, false, true)));
    }

    @Test void permittedChatDoesNotRequireBodyActivity() {
        Key chat = key(Trigger.DIRECT_CHAT, 1);
        assertEquals(Verdict.REQUEST, evaluate(chat, null, new Context(true, true, false, false, false, true)));
        assertEquals(Verdict.NOT_PERMITTED, evaluate(chat, null, new Context(true, true, false, true, true, false)));
        assertThrows(IllegalArgumentException.class, () -> new Key(companion, ModelGateway.Lane.CHAT, epoch,
                1, 2, 1, Trigger.NEW_GOAL));
    }

    @Test void revisionsAndCompanionIdentitySeparateDemands() {
        Key previous = key(Trigger.NEW_GOAL, 1);
        assertEquals(Verdict.REQUEST, evaluate(new Key(companion, ModelGateway.Lane.PLANNER, epoch,
                2, 2, 1, Trigger.NEW_GOAL), previous, ready));
        assertEquals(Verdict.REQUEST, evaluate(new Key(UUID.randomUUID(), ModelGateway.Lane.PLANNER, epoch,
                1, 2, 1, Trigger.NEW_GOAL), previous, ready));
        assertThrows(IllegalArgumentException.class, () -> key(Trigger.NEW_GOAL, -1));
    }
}
