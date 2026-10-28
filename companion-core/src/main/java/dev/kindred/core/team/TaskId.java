package dev.kindred.core.team;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: the name tag of one job in a team project.
 *
 * Technical: Strongly-typed shared project task identifier.
 */
public record TaskId(UUID id) {
    public TaskId {
        Objects.requireNonNull(id, "id cannot be null");
    }

    /** A job id from a raw id. */
    public static TaskId of(UUID id) {
        return new TaskId(id);
    }

    public static TaskId of(ProjectId projectId, String taskName) {
        java.util.Objects.requireNonNull(projectId, "projectId");
        java.util.Objects.requireNonNull(taskName, "taskName");
        return new TaskId(UUID.nameUUIDFromBytes((projectId.id() + ":" + taskName).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** A new, unique job id. */
    public static TaskId random() {
        return new TaskId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
