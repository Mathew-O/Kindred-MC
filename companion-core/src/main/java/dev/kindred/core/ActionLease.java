package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: a pass that lets one specific job act right now. Every job gets a new pass, and an old pass
 * stops working the moment the job is stopped or replaced, so a leftover callback can never make the player
 * do something.
 *
 * Technical: An authority token; possessing an old token does not grant permission to act.
 */
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
