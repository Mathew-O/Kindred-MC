package dev.kindred.core.team;

import java.util.Objects;

/** Strongly-typed shared project identifier. */
public record ProjectId(String id) {
    public ProjectId {
        Objects.requireNonNull(id, "id cannot be null");
        if (id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("ProjectId must be 1..64 characters");
        }
    }

    public static ProjectId of(String id) {
        return new ProjectId(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
