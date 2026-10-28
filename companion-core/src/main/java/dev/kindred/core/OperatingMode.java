package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: an older saved setting for companions (whether it stayed with its owner or worked alone,
 * and where it was allowed to work). It is kept so old saves and their restrictions still load; the new AI
 * players do not use it to decide anything.
 *
 * Technical: Legacy saved mode/scope. New AI-player autonomy is not selected by this enum; preserve old
 * access restrictions during migration.
 */
public record OperatingMode(UUID companionId, Mode mode, Scope scope, long revision) {
    public enum Mode { COMPANION, INDEPENDENT }
    public enum Scope { HOME, APPROVED }

    public OperatingMode {
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(scope);
        if (revision < 0) throw new IllegalArgumentException("Negative mode revision");
    }

    /** The starting setting: a companion that stays near home. */
    public static OperatingMode defaults(UUID companionId) {
        return new OperatingMode(companionId, Mode.COMPANION, Scope.APPROVED, 0);
    }

    /** The same setting with a different mode. */
    public OperatingMode withMode(Mode next) {
        return new OperatingMode(companionId, next, scope, Math.incrementExact(revision));
    }

    /** The same setting with a different work area. */
    public OperatingMode withScope(Scope next) {
        return new OperatingMode(companionId, mode, next, Math.incrementExact(revision));
    }
}
