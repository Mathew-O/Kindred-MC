package dev.kindred.persistence;

import dev.kindred.core.PlayerAccess;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: the saved facts about a native AI player that are not in Minecraft's own player file: who
 * may control it, how far its creation got (allocated, established or blocked), and why it is blocked if it
 * is. Its items and health live in the normal Minecraft save.
 *
 * Technical: Metadata only. Physical inventory and survival state remain in vanilla native player saves.
 */
public record StoredNativePlayer(StoredCompanion metadata, UUID controllerId, Phase phase, UUID saveReceipt,
                                 String blocker, long revision) {
    public enum Phase { ALLOCATED, ESTABLISHED, BLOCKED }
    public StoredNativePlayer {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(controllerId);
        Objects.requireNonNull(phase);
        Objects.requireNonNull(blocker);
        if (!metadata.companion().id().equals(metadata.companion().ownerId()) || metadata.companion().entityId() == null) {
            throw new IllegalArgumentException("Native metadata must self-own its legacy projection and map a body");
        }
        if (revision < 0 || (phase == Phase.ESTABLISHED && saveReceipt == null)) throw new IllegalArgumentException("Invalid native body metadata");
    }
    /** Who may control this player: its controller, and operators. */
    public PlayerAccess access() { return new PlayerAccess(metadata.companion().id(), Set.of(controllerId)); }
}
