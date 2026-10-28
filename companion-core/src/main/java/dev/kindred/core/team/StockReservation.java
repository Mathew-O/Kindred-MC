package dev.kindred.core.team;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: materials in a shared chest set aside for one job, so two players never count on the same
 * iron or wood.
 *
 * Technical: A durable material reservation against shared stock, preventing double-spending
 * across concurrent AI players.
 */
public record StockReservation(
        UUID reservationId,
        TaskId taskId,
        ProjectId projectId,
        UUID assignedPlayerId,
        String itemId,
        int quantity,
        String destination,
        ReservationStatus status,
        long revision
) {
    public enum ReservationStatus {
        ACTIVE,
        FULFILLED,
        RELEASED
    }

    public StockReservation {
        Objects.requireNonNull(reservationId, "reservationId cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(projectId, "projectId cannot be null");
        Objects.requireNonNull(itemId, "itemId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be positive");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Reservation revision cannot be negative");
        }
    }

    /** Sets materials aside for a job. */
    public static StockReservation active(
            UUID reservationId,
            TaskId taskId,
            ProjectId projectId,
            UUID assignedPlayerId,
            String itemId,
            int quantity,
            String destination
    ) {
        return new StockReservation(reservationId, taskId, projectId, assignedPlayerId, itemId, quantity, destination, ReservationStatus.ACTIVE, 0L);
    }

    /** The set-aside materials were used for the job. */
    public StockReservation fulfill(long nextRevision) {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("Only active reservations can be fulfilled");
        }
        return new StockReservation(reservationId, taskId, projectId, assignedPlayerId, itemId, quantity, destination, ReservationStatus.FULFILLED, nextRevision);
    }

    /** The set-aside materials are free again. */
    public StockReservation release(long nextRevision) {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("Only active reservations can be released");
        }
        return new StockReservation(reservationId, taskId, projectId, assignedPlayerId, itemId, quantity, destination, ReservationStatus.RELEASED, nextRevision);
    }
}
