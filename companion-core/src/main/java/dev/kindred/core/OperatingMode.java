package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/** Legacy saved mode/scope. New AI-player autonomy is not selected by this enum; preserve old access restrictions during migration. */
public record OperatingMode(UUID companionId, Mode mode, Scope scope, long revision) {
    public enum Mode { COMPANION, INDEPENDENT }
    public enum Scope { HOME, APPROVED }

    public OperatingMode {
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(scope);
        if (revision < 0) throw new IllegalArgumentException("Negative mode revision");
    }

    public static OperatingMode defaults(UUID companionId) {
        return new OperatingMode(companionId, Mode.COMPANION, Scope.APPROVED, 0);
    }

    public OperatingMode withMode(Mode next) {
        return new OperatingMode(companionId, next, scope, Math.incrementExact(revision));
    }

    public OperatingMode withScope(Scope next) {
        return new OperatingMode(companionId, mode, next, Math.incrementExact(revision));
    }
}
