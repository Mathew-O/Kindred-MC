package dev.kindred.core;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Catalog entry for an installed skill: what it needs, how long it may run, how long it may make no progress,
 * how many local recoveries it gets. A model may select a skill and its arguments; it cannot add one, change
 * these bounds or supply the success predicate.
 */
public record SkillDescriptor(String id, Set<String> requiredArguments, Set<String> optionalArguments,
                              Set<String> capabilities, Set<EditPolicy.Kind> permissions, Duration timeout,
                              Duration noProgressLimit, int retryBudget) {
    public static final int MAX_RETRY_BUDGET = 2;
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);

    public SkillDescriptor {
        if (id == null || !id.matches("[a-z][a-z0-9_]{1,31}")) throw new IllegalArgumentException("Skill IDs are lowercase identifiers");
        requiredArguments = Set.copyOf(Objects.requireNonNull(requiredArguments));
        optionalArguments = Set.copyOf(Objects.requireNonNull(optionalArguments));
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities));
        permissions = Set.copyOf(Objects.requireNonNull(permissions));
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(noProgressLimit);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) throw new IllegalArgumentException("Timeout must be within 30 minutes");
        if (noProgressLimit.isNegative() || noProgressLimit.isZero() || noProgressLimit.compareTo(timeout) > 0) {
            throw new IllegalArgumentException("No-progress limit must be positive and within the timeout");
        }
        if (retryBudget < 0 || retryBudget > MAX_RETRY_BUDGET) throw new IllegalArgumentException("Retry budget is 0.." + MAX_RETRY_BUDGET);
        for (String required : requiredArguments) {
            if (optionalArguments.contains(required)) throw new IllegalArgumentException("Argument " + required + " cannot be both required and optional");
        }
    }

    /** Strict schema: unknown arguments are rejected rather than ignored, so a proposal cannot smuggle intent. */
    public Map<String, String> validate(Map<String, String> arguments) {
        Objects.requireNonNull(arguments);
        Set<String> missing = new HashSet<>(requiredArguments);
        for (var entry : arguments.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null) throw new IllegalArgumentException("Null skill argument");
            if (!requiredArguments.contains(key) && !optionalArguments.contains(key)) throw new IllegalArgumentException("Unknown argument " + key + " for " + id);
            if (entry.getValue().length() > 256) throw new IllegalArgumentException("Argument " + key + " is too long");
            missing.remove(key);
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing arguments for " + id + ": " + missing);
        return Map.copyOf(arguments);
    }
}
