package dev.kindred.core.team;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An individual task within a shared team project with dependencies,
 * single-assignee ownership, material reservations, and completion evidence.
 */
public record SharedTask(
        TaskId id,
        ProjectId projectId,
        String title,
        String skill,
        Map<String, String> arguments,
        Set<TaskId> dependencies,
        UUID assignedPlayerId,
        TaskState state,
        Optional<StockReservation> stockReservation,
        long revision,
        long claimedAtTick,
        long leaseDurationTicks,
        String completionEvidence
) {
    public enum TaskState {
        OPEN,
        CLAIMED,
        IN_PROGRESS,
        COMPLETED,
        REVOKED,
        FAILED
    }

    public SharedTask {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(projectId, "projectId cannot be null");
        Objects.requireNonNull(title, "title cannot be null");
        Objects.requireNonNull(skill, "skill cannot be null");
        arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(Map.copyOf(arguments));
        dependencies = dependencies == null ? Set.of() : Collections.unmodifiableSet(Set.copyOf(dependencies));
        Objects.requireNonNull(state, "state cannot be null");
        stockReservation = stockReservation == null ? Optional.empty() : stockReservation;
        if (revision < 0) throw new IllegalArgumentException("Task revision cannot be negative");
    }

    public static SharedTask open(
            TaskId id,
            ProjectId projectId,
            String title,
            String skill,
            Map<String, String> arguments,
            Set<TaskId> dependencies
    ) {
        return new SharedTask(id, projectId, title, skill, arguments, dependencies, null,
                TaskState.OPEN, Optional.empty(), 0L, 0L, 0L, null);
    }

    public SharedTask claim(UUID playerId, long tick, long leaseDuration, Optional<StockReservation> reservation) {
        Objects.requireNonNull(playerId, "playerId");
        if (state != TaskState.OPEN && state != TaskState.REVOKED) {
            throw new IllegalStateException("Cannot claim task in state " + state);
        }
        return new SharedTask(id, projectId, title, skill, arguments, dependencies, playerId,
                TaskState.CLAIMED, reservation, revision + 1, tick, leaseDuration, null);
    }

    public SharedTask start() {
        if (state != TaskState.CLAIMED) {
            throw new IllegalStateException("Cannot start task in state " + state);
        }
        return new SharedTask(id, projectId, title, skill, arguments, dependencies, assignedPlayerId,
                TaskState.IN_PROGRESS, stockReservation, revision + 1, claimedAtTick, leaseDurationTicks, null);
    }

    public SharedTask complete(String evidence) {
        if (state != TaskState.CLAIMED && state != TaskState.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete task in state " + state);
        }
        return new SharedTask(id, projectId, title, skill, arguments, dependencies, assignedPlayerId,
                TaskState.COMPLETED, stockReservation, revision + 1, claimedAtTick, leaseDurationTicks, evidence);
    }

    public SharedTask revoke(String reason) {
        return new SharedTask(id, projectId, title, skill, arguments, dependencies, null,
                TaskState.REVOKED, Optional.empty(), revision + 1, 0L, 0L, reason);
    }

    public SharedTask reopen() {
        return new SharedTask(id, projectId, title, skill, arguments, dependencies, null,
                TaskState.OPEN, Optional.empty(), revision + 1, 0L, 0L, null);
    }

    public SharedTask fail(String reason) {
        return new SharedTask(id, projectId, title, skill, arguments, dependencies, assignedPlayerId,
                TaskState.FAILED, stockReservation, revision + 1, claimedAtTick, leaseDurationTicks, reason);
    }

    public boolean isExpired(long currentTick) {
        return state == TaskState.CLAIMED || state == TaskState.IN_PROGRESS
                ? leaseDurationTicks > 0 && currentTick > (claimedAtTick + leaseDurationTicks)
                : false;
    }
}
