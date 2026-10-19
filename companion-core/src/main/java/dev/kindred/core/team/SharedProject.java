package dev.kindred.core.team;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A cooperative shared project containing dependent tasks, stock reservations,
 * and trackable progress towards an overarching survival goal.
 */
public record SharedProject(
        ProjectId id,
        String name,
        String description,
        Map<TaskId, SharedTask> tasks,
        ProjectState state,
        long revision,
        long createdAtMs,
        long updatedAtMs
) {
    public enum ProjectState {
        OPEN,
        IN_PROGRESS,
        BLOCKED,
        COMPLETED,
        CANCELLED
    }

    public SharedProject {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        tasks = tasks == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(tasks));
        Objects.requireNonNull(state, "state cannot be null");
        if (revision < 0) throw new IllegalArgumentException("Project revision cannot be negative");
    }

    public static SharedProject create(ProjectId id, String name, String description) {
        long now = Instant.now().toEpochMilli();
        return new SharedProject(id, name, description, Map.of(), ProjectState.OPEN, 0L, now, now);
    }

    public SharedProject withTask(SharedTask task) {
        Objects.requireNonNull(task, "task");
        Map<TaskId, SharedTask> updated = new HashMap<>(tasks);
        updated.put(task.id(), task);
        long now = Instant.now().toEpochMilli();
        return new SharedProject(id, name, description, updated, state, revision + 1, createdAtMs, now);
    }

    public SharedProject withState(ProjectState nextState) {
        Objects.requireNonNull(nextState, "nextState");
        long now = Instant.now().toEpochMilli();
        return new SharedProject(id, name, description, tasks, nextState, revision + 1, createdAtMs, now);
    }

    public Optional<SharedTask> getTask(TaskId taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public boolean areAllTasksCompleted() {
        if (tasks.isEmpty()) return false;
        return tasks.values().stream().allMatch(t -> t.state() == SharedTask.TaskState.COMPLETED);
    }
}
