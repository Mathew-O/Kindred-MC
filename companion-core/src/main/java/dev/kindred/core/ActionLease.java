package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/** An authority token; possessing an old token does not grant permission to act. */
public record ActionLease(UUID companionId, UUID taskId, UUID actionId, UUID sessionEpoch,
                          long bodyGeneration, long intentRevision, long taskRevision) {
    public ActionLease {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(sessionEpoch, "sessionEpoch");
        if (bodyGeneration < 0 || intentRevision < 0 || taskRevision < 0) {
            throw new IllegalArgumentException("Fences must be non-negative");
        }
    }
}
