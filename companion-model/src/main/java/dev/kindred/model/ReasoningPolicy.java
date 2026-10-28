package dev.kindred.model;

import dev.kindred.core.ProposalFence;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: decides whether a player should ask an AI model anything right now: only when one is set
 * up, there is budget left, nothing is already waiting, it is allowed to, and something has actually changed
 * (never on an ordinary tick).
 *
 * Technical: Pure admission policy. The runtime supplies trusted facts; this policy cannot grant body
 * authority.
 */
public final class ReasoningPolicy {
    public enum Trigger { DIRECT_CHAT, NEW_GOAL, PLAN_INVALIDATED, LOCAL_RECOVERY_EXHAUSTED, ROUTINE_TICK }
    public enum Verdict { REQUEST, DISABLED, BUDGET_EXHAUSTED, IN_FLIGHT, NOT_PERMITTED, NO_CHANGE }

    /** Store the last admitted key per companion/lane, not an unbounded history of world observations. */
    public record Key(UUID companionId, ModelGateway.Lane lane, UUID epoch,
                      long intentRevision, long taskRevision, long observationRevision, Trigger trigger) {
        public Key {
            Objects.requireNonNull(companionId);
            Objects.requireNonNull(lane);
            Objects.requireNonNull(epoch);
            Objects.requireNonNull(trigger);
            if (intentRevision < 0 || taskRevision < 0 || observationRevision < 0) {
                throw new IllegalArgumentException("Negative reasoning revision");
            }
            if ((lane == ModelGateway.Lane.CHAT) != (trigger == Trigger.DIRECT_CHAT)) {
                throw new IllegalArgumentException("Direct conversation and planning use separate lanes");
            }
        }

        /** What a request would be about, read from the player's current plan. */
        public static Key from(UUID companionId, ModelGateway.Lane lane, ProposalFence fence,
                               long observationRevision, Trigger trigger) {
            return new Key(companionId, lane, fence.sessionEpoch(), fence.intentRevision(),
                    fence.taskRevision(), observationRevision, trigger);
        }
    }

    public record Context(boolean providerEnabled, boolean budgetAvailable, boolean requestInFlight,
                          boolean workPermitted, boolean awake, boolean chatPermitted) { }

    private ReasoningPolicy() { }

    /** Call before reserving a model request; update lastAdmitted only if submission is admitted. */
    public static Verdict evaluate(Key demand, Key lastAdmitted, Context context) {
        Objects.requireNonNull(demand);
        Objects.requireNonNull(context);
        if (!context.providerEnabled()) return Verdict.DISABLED;
        if (!context.budgetAvailable()) return Verdict.BUDGET_EXHAUSTED;
        if (context.requestInFlight()) return Verdict.IN_FLIGHT;
        boolean chat = demand.trigger() == Trigger.DIRECT_CHAT;
        if (chat ? !context.chatPermitted() : !context.workPermitted() || !context.awake()) {
            return Verdict.NOT_PERMITTED;
        }
        if (demand.trigger() == Trigger.ROUTINE_TICK || demand.equals(lastAdmitted)) return Verdict.NO_CHANGE;
        return Verdict.REQUEST;
    }
}
