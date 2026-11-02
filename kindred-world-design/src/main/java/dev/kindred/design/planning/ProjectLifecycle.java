package dev.kindred.design.planning;

import dev.kindred.design.model.DesignModel.ExecutionStatus;
import dev.kindred.design.model.SemanticDesign.RevisionStatus;
import java.util.*;

/** Explicit state machine; it reports blocked states and never performs world mutation. */
public final class ProjectLifecycle {
    public record State(RevisionStatus revision, ExecutionStatus execution, String reason) {
        public State { Objects.requireNonNull(revision); Objects.requireNonNull(execution); reason = reason == null ? "" : reason; }
    }
    public State transition(State current, RevisionStatus revision, ExecutionStatus execution, String reason) {
        Objects.requireNonNull(current); Objects.requireNonNull(revision); Objects.requireNonNull(execution);
        if (!revisionAllowed(current.revision(), revision)) throw new IllegalStateException("Invalid revision transition");
        if (!executionAllowed(current.execution(), execution)) throw new IllegalStateException("Invalid execution transition");
        if ((execution == ExecutionStatus.BLOCKED || execution == ExecutionStatus.WAITING_RESOURCES) && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("Blocked state needs a stable reason");
        if (current.revision() != RevisionStatus.ACCEPTED && (execution == ExecutionStatus.READY || execution == ExecutionStatus.BUILDING || execution == ExecutionStatus.VERIFYING))
            throw new IllegalStateException("Only accepted revisions may execute");
        return new State(revision, execution, reason);
    }
    private static boolean revisionAllowed(RevisionStatus from, RevisionStatus to) {
        return from == to || switch (from) {
            case DRAFT -> to == RevisionStatus.VALIDATING || to == RevisionStatus.SUPERSEDED;
            case VALIDATING -> to == RevisionStatus.DRAFT || to == RevisionStatus.VALIDATED || to == RevisionStatus.SUPERSEDED;
            case VALIDATED -> to == RevisionStatus.ACCEPTED || to == RevisionStatus.DRAFT || to == RevisionStatus.SUPERSEDED;
            case ACCEPTED -> to == RevisionStatus.SUPERSEDED;
            case SUPERSEDED -> false;
        };
    }
    private static boolean executionAllowed(ExecutionStatus from, ExecutionStatus to) {
        if (from == to) return true;
        if (to == ExecutionStatus.CANCELLED || to == ExecutionStatus.PAUSED || to == ExecutionStatus.RECONCILING || to == ExecutionStatus.BLOCKED) return true;
        return switch (from) {
            case UNSCHEDULED -> to == ExecutionStatus.WAITING_RESOURCES || to == ExecutionStatus.READY;
            case WAITING_RESOURCES -> to == ExecutionStatus.READY;
            case READY -> to == ExecutionStatus.BUILDING;
            case BUILDING -> to == ExecutionStatus.VERIFYING;
            case VERIFYING -> to == ExecutionStatus.COMPLETED || to == ExecutionStatus.READY;
            case PAUSED, BLOCKED, RECONCILING -> to == ExecutionStatus.READY || to == ExecutionStatus.WAITING_RESOURCES;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
