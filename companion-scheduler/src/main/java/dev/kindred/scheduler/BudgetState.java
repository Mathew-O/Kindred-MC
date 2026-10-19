package dev.kindred.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable prepaid accounting. Persist each transition atomically before granting its effect.
 * chargedMillis excludes outstanding; the outstanding slice is already unavailable to another
 * reservation. Native execution must enforce both monotonic expiry and the wall-clock deadline.
 */
public record BudgetState(
        UUID companionId,
        AccountingPeriod period,
        long chargedMillis,
        long observedActiveMillis,
        long sequence,
        Optional<Reservation> outstanding) {

    public static final Duration MAX_SLICE = Duration.ofSeconds(5);

    public BudgetState {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(outstanding, "outstanding");
        if (chargedMillis < 0 || observedActiveMillis < 0 || observedActiveMillis > chargedMillis
                || sequence < 0) {
            throw new IllegalArgumentException("Invalid accounting totals or sequence");
        }
        outstanding.ifPresent(reservation -> {
            if (!period.contains(reservation.reservedAt()) || reservation.expiresAt().isAfter(period.endExclusive())) {
                throw new IllegalArgumentException("Reservation crosses its accounting period");
            }
        });
    }

    public static BudgetState empty(UUID companionId, AccountingPeriod period) {
        return new BudgetState(companionId, period, 0, 0, 0, Optional.empty());
    }

    public Duration remainingAllowance(Duration cap) {
        long capMillis = ActivitySchedule.requirePositiveMillis(cap, "cap");
        long afterCharged = Math.max(0, capMillis - chargedMillis);
        return Duration.ofMillis(Math.max(0, afterCharged - outstanding.map(Reservation::reservedMillis).orElse(0L)));
    }

    /**
     * Returns unchanged when no whole millisecond is affordable. hardDeadline must be the
     * current eligible interval's end; this low-level accounting method does not grant eligibility.
     */
    public BudgetState reserve(UUID id, Duration requested, Duration cap, Instant now, Instant hardDeadline) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(hardDeadline, "hardDeadline");
        if (outstanding.isPresent()) {
            throw new IllegalStateException("Reconcile the outstanding reservation before reserving again");
        }
        long requestedMillis = ActivitySchedule.requirePositiveMillis(requested, "requested");
        if (!period.contains(now)) {
            return this;
        }
        Instant deadline = hardDeadline.isBefore(period.endExclusive()) ? hardDeadline : period.endExclusive();
        long availableMillis = Math.max(0, Duration.between(now, deadline).toMillis());
        long grant = Math.min(Math.min(requestedMillis, MAX_SLICE.toMillis()),
                Math.min(remainingAllowance(cap).toMillis(), availableMillis));
        if (grant == 0) {
            return this;
        }
        return new BudgetState(companionId, period, chargedMillis, observedActiveMillis, nextSequence(),
                Optional.of(new Reservation(id, grant, now, now.plusMillis(grant))));
    }

    /** Reconcile actual active time measured monotonically. Unused prepaid time becomes available. */
    public BudgetState settle(UUID id, Duration observedActive) {
        Reservation reservation = requireReservation(id);
        Objects.requireNonNull(observedActive, "observedActive");
        long actual = observedActive.toMillis();
        if (observedActive.isNegative() || actual > reservation.reservedMillis()
                || !observedActive.equals(Duration.ofMillis(actual))) {
            throw new IllegalArgumentException("Observed duration must be whole milliseconds within the reserved slice");
        }
        return new BudgetState(companionId, period, Math.addExact(chargedMillis, actual),
                Math.addExact(observedActiveMillis, actual), nextSequence(), Optional.empty());
    }

    /** Reconciliation and replacement must be saved in ONE persistence transaction. */
    public BudgetState renew(UUID previousId, Duration observedActive, UUID nextId, Duration requested,
                             Duration cap, Instant now, Instant hardDeadline) {
        if (Objects.requireNonNull(nextId, "nextId").equals(previousId)) {
            throw new IllegalArgumentException("Renewal requires a fresh reservation identity");
        }
        return settle(previousId, observedActive).reserve(nextId, requested, cap, now, hardDeadline);
    }

    /** Idempotent after the resulting state is persisted. Unobserved crash time is never invented. */
    public BudgetState recoverAfterCrash() {
        if (outstanding.isEmpty()) {
            return this;
        }
        return new BudgetState(companionId, period,
                Math.addExact(chargedMillis, outstanding.orElseThrow().reservedMillis()),
                observedActiveMillis, nextSequence(), Optional.empty());
    }

    private Reservation requireReservation(UUID id) {
        Reservation reservation = outstanding.orElseThrow(() -> new IllegalStateException("No outstanding reservation"));
        if (!reservation.id().equals(id)) {
            throw new IllegalStateException("Stale reservation identity");
        }
        return reservation;
    }

    private long nextSequence() {
        return Math.incrementExact(sequence);
    }
}
