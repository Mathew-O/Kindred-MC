package dev.kindred.core;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A resumable boundary: the goal, absolute progress and the task revision it belongs to. Resume recomputes
 * routes and rechecks permissions from here; it never redoes the whole original request. Progress holds
 * small immutable strings only, never live world objects.
 */
public record Checkpoint(String skillId, UUID taskId, long taskRevision, Map<String, String> arguments,
                         Map<String, String> progress, long at) {
    public static final int MAX_ENTRIES = 32;

    public Checkpoint {
        Objects.requireNonNull(skillId);
        Objects.requireNonNull(taskId);
        arguments = bounded(arguments, "arguments");
        progress = bounded(progress, "progress");
        if (taskRevision < 0) throw new IllegalArgumentException("Negative task revision");
    }

    private static Map<String, String> bounded(Map<String, String> map, String name) {
        Objects.requireNonNull(map, name);
        if (map.size() > MAX_ENTRIES) throw new IllegalArgumentException(name + " exceeds " + MAX_ENTRIES + " entries");
        for (var entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getKey().length() > 64 || entry.getValue().length() > 256) {
                throw new IllegalArgumentException("Checkpoint " + name + " entries must be short non-null strings");
            }
        }
        return Map.copyOf(map);
    }
}
