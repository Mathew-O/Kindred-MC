package dev.kindred.core;

import java.util.Objects;

/**
 * In plain terms: the rulebook for whether a player may break a block, place a block or use a chest right
 * now. It checks the server's switches and the work area and storage it was given, and says yes or gives the
 * reason for no. The real block is still checked again in the game before anything changes.
 *
 * Technical: Whether one world mutation is permitted by the saved setup and the server's standing decisions.
 * This is evaluated immediately before a mutation on the server thread, after claims and the lease; it is
 * never a reason to skip the adapter's identity/version recheck of the actual block.
 */
public final class EditPolicy {
    public enum Kind { BREAK, PLACE, CONTAINER }

    /**
     * The World Record's kind for a change of the server's switch for free travel or free items: the switch as subject,
     * {@code allowed} and {@code by} (who changed it) as data, the operator as actor when a player (the audit, D6).
     */
    public static final String POLICY_SET = "policy.set";

    public enum Verdict {
        ALLOWED, NO_SETUP, WRONG_DIMENSION, OUTSIDE_AREA, OUTSIDE_PATCH, WRONG_RESOURCE, NOT_ASSIGNED_STORAGE,
        EDITS_DISABLED, PROTECTION_UNKNOWN
    }

    public record Decision(Verdict verdict, String reason) {
        public boolean allowed() { return verdict == Verdict.ALLOWED; }
    }

    /**
     * Server-level facts that no companion setting can change. {@code teleport} and {@code freeItems} are the switches for
     * free travel and free items (teleports, kits, creative-style storage; the owner's mod-support decision 6): forbidden
     * by default, and when an operator allows one it is allowed to every player alike, human or AI, by a rule announced
     * to all (D6). Nothing grants either to an AI player alone.
     */
    public record ServerPolicy(boolean autonomousEditsEnabled, boolean protectionCompatibilityKnown, boolean teleport, boolean freeItems) {
        /** The edit switches alone: free travel and free items forbidden. */
        public ServerPolicy(boolean autonomousEditsEnabled, boolean protectionCompatibilityKnown) {
            this(autonomousEditsEnabled, protectionCompatibilityKnown, false, false);
        }

        /** Decision D4 is deferred: autonomous block/container edits are disabled and protection compatibility is unknown. */
        public static ServerPolicy deferredD4() { return new ServerPolicy(false, false); }

        /** The same policy with world edits {@code enabled} and protection known, its travel and item switches kept. */
        public ServerPolicy withEdits(boolean enabled) { return new ServerPolicy(enabled, true, teleport, freeItems); }

        public ServerPolicy withTeleport(boolean allowed) { return new ServerPolicy(autonomousEditsEnabled, protectionCompatibilityKnown, allowed, freeItems); }

        public ServerPolicy withFreeItems(boolean allowed) { return new ServerPolicy(autonomousEditsEnabled, protectionCompatibilityKnown, teleport, allowed); }

        /** The standing rule for free travel and free items, as announced to every player, the same words for all (D6). */
        public java.util.List<String> announcement() {
            return java.util.List.of(
                    teleport ? "Teleporting is allowed on this server, for every player alike, human or AI."
                            : "No teleporting on this server, for anyone, human or AI: everyone travels on foot, by boat or by portal.",
                    freeItems ? "Free items (kits, creative storage) are allowed on this server, for every player alike, human or AI."
                            : "No free items on this server, for anyone, human or AI: everything is mined, crafted, traded or won.");
        }
    }

    private EditPolicy() { }

    /** Says whether one edit (break, place or chest use) at one spot is allowed, and why not if it isn't. */
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
