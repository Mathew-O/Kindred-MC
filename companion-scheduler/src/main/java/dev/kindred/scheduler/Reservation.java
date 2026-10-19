package dev.kindred.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persist before activating. Acknowledgement after expiresAt conveys no authority to work. */
public record Reservation(UUID id, long reservedMillis, Instant reservedAt, Instant expiresAt) {
    public Reservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reservedAt, "reservedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (reservedMillis <= 0 || reservedMillis > BudgetState.MAX_SLICE.toMillis()
                || !expiresAt.equals(reservedAt.plusMillis(reservedMillis))) {
            throw new IllegalArgumentException("Reservation must represent a slice of at most five seconds");
        }
    }

    /** Runtime deadlines must additionally use a monotonic clock; wall rollback cannot renew a lease. */
    public Duration availableAt(Instant now) {
        if (now.isBefore(reservedAt) || !now.isBefore(expiresAt)) {
            return Duration.ZERO;
        }
        return Duration.between(now, expiresAt);
    }
}
