package dev.kindred.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Trusted request metadata attached by the gateway, not authority supplied by the model. */
public record ProposalFence(UUID sessionEpoch, long intentRevision, long taskRevision,
                            Instant deadline) {
    public ProposalFence {
        Objects.requireNonNull(sessionEpoch, "sessionEpoch");
        Objects.requireNonNull(deadline, "deadline");
        if (intentRevision < 0 || taskRevision < 0) {
            throw new IllegalArgumentException("Revisions must be non-negative");
        }
    }
}
