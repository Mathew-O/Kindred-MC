package dev.kindred.core;

import java.util.Objects;

/**
 * Whether one world mutation is permitted by the saved setup and the server's standing decisions. This is
 * evaluated immediately before a mutation on the server thread, after claims and the lease; it is never a
 * reason to skip the adapter's identity/version recheck of the actual block.
 */
public final class EditPolicy {
    public enum Kind { BREAK, PLACE, CONTAINER }

    public enum Verdict {
        ALLOWED, NO_SETUP, WRONG_DIMENSION, OUTSIDE_AREA, OUTSIDE_PATCH, WRONG_RESOURCE, NOT_ASSIGNED_STORAGE,
        EDITS_DISABLED, PROTECTION_UNKNOWN
    }

    public record Decision(Verdict verdict, String reason) {
        public boolean allowed() { return verdict == Verdict.ALLOWED; }
    }

    /** Server-level facts that no companion setting can change. */
    public record ServerPolicy(boolean autonomousEditsEnabled, boolean protectionCompatibilityKnown) {
        /** Decision D4 is deferred: autonomous block/container edits are disabled and protection compatibility is unknown. */
        public static ServerPolicy deferredD4() { return new ServerPolicy(false, false); }
    }

    private EditPolicy() { }

    public static Decision evaluate(WorkSetup setup, Kind kind, String dimension, WorkSetup.BlockPoint target,
                                    String blockId, ServerPolicy server) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(target);
        Objects.requireNonNull(server);
        if (setup == null || setup.area() == null) return new Decision(Verdict.NO_SETUP, "No work area is assigned.");
        if (!setup.dimension().equals(dimension)) return new Decision(Verdict.WRONG_DIMENSION, "The target is in another dimension.");
        if (!setup.area().contains(target)) return new Decision(Verdict.OUTSIDE_AREA, "The target is outside the assigned work area.");
        if (!server.protectionCompatibilityKnown()) {
            return new Decision(Verdict.PROTECTION_UNKNOWN, "Protection-plugin compatibility is unverified on this server; edits are blocked.");
        }
        return switch (kind) {
            case CONTAINER -> {
                if (setup.storage() == null || !setup.storage().position().equals(target)) {
                    yield new Decision(Verdict.NOT_ASSIGNED_STORAGE, "Only the assigned storage container may be used.");
                }
                if (blockId != null && !blockId.equals(setup.storage().blockId())) {
                    yield new Decision(Verdict.NOT_ASSIGNED_STORAGE, "The assigned container's block identity changed; reassign storage.");
                }
                yield server.autonomousEditsEnabled() ? new Decision(Verdict.ALLOWED, "Assigned storage.")
                        : new Decision(Verdict.EDITS_DISABLED, "Autonomous container use is disabled until decision D4 is recorded.");
            }
            case BREAK -> {
                if (setup.patch() == null || !setup.patch().contains(target)) {
                    yield new Decision(Verdict.OUTSIDE_PATCH, "Only blocks inside the designated resource patch may be harvested.");
                }
                if (blockId == null || !blockId.equals(setup.patch().resourceId())) {
                    yield new Decision(Verdict.WRONG_RESOURCE, "Only the patch's designated resource may be harvested.");
                }
                yield server.autonomousEditsEnabled() ? new Decision(Verdict.ALLOWED, "Designated resource inside the patch.")
                        : new Decision(Verdict.EDITS_DISABLED, "Autonomous block breaking is disabled until decision D4 is recorded.");
            }
            case PLACE -> server.autonomousEditsEnabled() ? new Decision(Verdict.ALLOWED, "Inside the work area.")
                    : new Decision(Verdict.EDITS_DISABLED, "Autonomous block placement is disabled until decision D4 is recorded.");
        };
    }
}
