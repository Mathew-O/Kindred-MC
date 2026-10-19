package dev.kindred.core.team;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks available physical resources in shared storage and active reservations,
 * strictly preventing double-spending across concurrent team members.
 */
public class StockLedger {
    private final Map<String, Integer> totalStock = new ConcurrentHashMap<>();
    private final Map<UUID, StockReservation> reservations = new ConcurrentHashMap<>();

    public synchronized void registerStock(String itemId, int count) {
        Objects.requireNonNull(itemId, "itemId");
        if (count < 0) throw new IllegalArgumentException("Stock cannot be negative");
        totalStock.put(itemId, count);
    }

    public synchronized void addStock(String itemId, int delta) {
        Objects.requireNonNull(itemId, "itemId");
        totalStock.merge(itemId, delta, (oldVal, addVal) -> Math.max(0, oldVal + addVal));
    }

    public synchronized int totalStock(String itemId) {
        return totalStock.getOrDefault(itemId, 0);
    }

    public synchronized int reservedCount(String itemId) {
        int sum = 0;
        for (var res : reservations.values()) {
            if (res.status() == StockReservation.ReservationStatus.ACTIVE && res.itemId().equals(itemId)) {
                sum += res.quantity();
            }
        }
        return sum;
    }

    public synchronized int unreservedCount(String itemId) {
        int total = totalStock(itemId);
        int reserved = reservedCount(itemId);
        return Math.max(0, total - reserved);
    }

    /**
     * Attempts to place an active reservation against unreserved stock.
     * Returns empty if requested quantity exceeds unreserved stock.
     */
    public synchronized Optional<StockReservation> tryReserve(
            UUID reservationId,
            TaskId taskId,
            ProjectId projectId,
            UUID playerId,
            String itemId,
            int quantity,
            String destination
    ) {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(itemId, "itemId");
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");

        if (unreservedCount(itemId) < quantity) {
            return Optional.empty(); // Refused: would double-spend
        }

        StockReservation reservation = StockReservation.active(
                reservationId, taskId, projectId, playerId, itemId, quantity, destination
        );
        reservations.put(reservationId, reservation);
        return Optional.of(reservation);
    }

    public synchronized boolean release(UUID reservationId) {
        StockReservation current = reservations.get(reservationId);
        if (current != null && current.status() == StockReservation.ReservationStatus.ACTIVE) {
            reservations.put(reservationId, current.release(current.revision() + 1));
            return true;
        }
        return false;
    }

    public synchronized boolean fulfill(UUID reservationId) {
        StockReservation current = reservations.get(reservationId);
        if (current != null && current.status() == StockReservation.ReservationStatus.ACTIVE) {
            // Deduct the consumed physical stock
            totalStock.merge(current.itemId(), -current.quantity(), (oldVal, subVal) -> Math.max(0, oldVal + subVal));
            reservations.put(reservationId, current.fulfill(current.revision() + 1));
            return true;
        }
        return false;
    }

    public synchronized void releaseAllForPlayer(UUID playerId) {
        if (playerId == null) return;
        for (var entry : reservations.entrySet()) {
            StockReservation res = entry.getValue();
            if (res.status() == StockReservation.ReservationStatus.ACTIVE && playerId.equals(res.assignedPlayerId())) {
                entry.setValue(res.release(res.revision() + 1));
            }
        }
    }

    public synchronized Optional<StockReservation> getReservation(UUID reservationId) {
        return Optional.ofNullable(reservations.get(reservationId));
    }

    public synchronized List<StockReservation> getActiveReservations() {
        return reservations.values().stream()
                .filter(r -> r.status() == StockReservation.ReservationStatus.ACTIVE)
                .toList();
    }

    public synchronized Map<String, Integer> snapshotStock() {
        return Collections.unmodifiableMap(new HashMap<>(totalStock));
    }
}
