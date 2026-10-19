package dev.kindred.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Short prepaid activity boundary. Construct only from the durable reservation acknowledgement;
 * this value does not prove persistence or grant admission/world authority. Runtime must revoke
 * its action lease on the first failed check, never resurrect a permit after a failed deadline.
 */
public final class ActivityPermit {
    private final UUID playerId;
    private final Reservation reservation;
    private final long sequence;
    private final AccountingPeriod period;
    private final Instant acknowledgedAt;
    private final long startedNanos;
    private final long availableNanos;

    private ActivityPermit(BudgetState state, Reservation reservation, Instant acknowledgedAt, long nanos, long millis) {
        playerId = state.companionId();
        this.reservation = reservation;
        sequence = state.sequence();
        period = state.period();
        this.acknowledgedAt = acknowledgedAt;
        startedNanos = nanos;
        availableNanos = Duration.ofMillis(millis).toNanos();
    }

    public static Optional<ActivityPermit> acknowledged(BudgetState state, UUID expectedReservation, Instant wall, long nanos) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(expectedReservation);
        Objects.requireNonNull(wall);
        Reservation reserved = state.outstanding().orElseThrow(() -> new IllegalArgumentException("No acknowledged reservation"));
        if (!reserved.id().equals(expectedReservation)) throw new IllegalArgumentException("Unexpected reservation acknowledgement");
        long millis = reserved.availableAt(wall).toMillis();
        return millis <= 0 ? Optional.empty() : Optional.of(new ActivityPermit(state, reserved, wall, nanos, millis));
    }

    public UUID playerId() { return playerId; }
    public UUID reservationId() { return reservation.id(); }
    public long sequence() { return sequence; }
    public Duration maximumActiveTime() { return Duration.ofNanos(availableNanos); }

    public boolean allows(UUID player, Instant wall, long nanos) {
        Objects.requireNonNull(wall);
        // Subtraction supports System.nanoTime's normal signed wrap; a backwards sample fails closed.
        long elapsed = nanos - startedNanos;
        return playerId.equals(player) && elapsed >= 0 && elapsed < availableNanos
                && !wall.isBefore(acknowledgedAt) && wall.isBefore(reservation.expiresAt());
    }

    /** Round known active intervals up; uncertainty must not become a refundable zero. */
    public Optional<Duration> observedUntil(long nanos) {
        long elapsed = nanos - startedNanos;
        if (elapsed < 0) return Optional.empty();
        long bounded = Math.min(elapsed, availableNanos);
        return Optional.of(Duration.ofMillis((bounded + 999_999) / 1_000_000));
    }

    public BudgetState settle(BudgetState acknowledged, long nanos) {
        Objects.requireNonNull(acknowledged);
        if (!acknowledged.companionId().equals(playerId) || acknowledged.sequence() != sequence || !acknowledged.period().equals(period)
                || acknowledged.outstanding().isEmpty()
                || !acknowledged.outstanding().orElseThrow().equals(reservation)) {
            throw new IllegalArgumentException("Stale or foreign activity settlement");
        }
        return observedUntil(nanos).map(observed -> acknowledged.settle(reservation.id(), observed))
                .orElseGet(acknowledged::recoverAfterCrash);
    }
}
