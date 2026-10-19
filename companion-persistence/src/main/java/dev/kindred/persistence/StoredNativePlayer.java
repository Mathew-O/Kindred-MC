package dev.kindred.persistence;

import dev.kindred.core.PlayerAccess;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Metadata only. Physical inventory and survival state remain in vanilla native player saves. */
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
    public PlayerAccess access() { return new PlayerAccess(metadata.companion().id(), Set.of(controllerId)); }
}
