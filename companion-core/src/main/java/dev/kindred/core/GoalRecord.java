package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/** A durable intention, separate from the skill that happens to be running. */
public record GoalRecord(UUID id, UUID companionId, Origin origin, String description, String outcome,
                         int priority, Status status, long revision) {
    public enum Origin { OWNER, PERSONAL }
    public enum Status { OPEN, BLOCKED, DONE }

    public GoalRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(status);
        if (description == null || description.isBlank() || description.length() > 200) throw new IllegalArgumentException("Goal description must be 1..200 characters");
        if (outcome == null || outcome.isBlank() || outcome.length() > 200) throw new IllegalArgumentException("Goal outcome must be 1..200 characters");
        if (priority < 0 || priority > 100) throw new IllegalArgumentException("Priority must be 0..100");
        if (revision < 0) throw new IllegalArgumentException("Negative goal revision");
    }

    public GoalRecord advance(Status next) {
        return new GoalRecord(id, companionId, origin, description, outcome, priority, next, Math.incrementExact(revision));
    }
}
