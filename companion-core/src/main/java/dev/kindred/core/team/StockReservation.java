package dev.kindred.core.team;

import java.util.Objects;
import java.util.UUID;

/**
 * A durable material reservation against shared stock, preventing double-spending
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

    public StockReservation fulfill(long nextRevision) {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("Only active reservations can be fulfilled");
        }
        return new StockReservation(reservationId, taskId, projectId, assignedPlayerId, itemId, quantity, destination, ReservationStatus.FULFILLED, nextRevision);
    }

    public StockReservation release(long nextRevision) {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("Only active reservations can be released");
        }
        return new StockReservation(reservationId, taskId, projectId, assignedPlayerId, itemId, quantity, destination, ReservationStatus.RELEASED, nextRevision);
    }
}
