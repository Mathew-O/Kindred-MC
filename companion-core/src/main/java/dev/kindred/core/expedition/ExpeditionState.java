package dev.kindred.core.expedition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: a player's trip away from home: where home is, how far it may roam, whether it is out
 * exploring or on its way back, the breadcrumb trail it left to find its way home, and the places it found.
 *
 * Technical: State machine managing an AI player's active expedition, home anchor, breadcrumbs, and
 * discovered waypoints.
 */
public final class ExpeditionState {
    private final Waypoint homeAnchor;
    private final double maxRadius;
    private ExpeditionStatus status;
    private ReturnReason returnReason;
    private final List<Waypoint> breadcrumbs;
    private final List<Waypoint> knownWaypoints;

    /** A trip plan starting at home, with how far it may roam. */
    public ExpeditionState(Waypoint homeAnchor, double maxRadius) {
        this.homeAnchor = Objects.requireNonNull(homeAnchor, "homeAnchor cannot be null");
        this.maxRadius = maxRadius <= 0 ? 64.0 : maxRadius;
        this.status = ExpeditionStatus.IDLE_AT_BASE;
        this.returnReason = ReturnReason.NONE;
        this.breadcrumbs = new ArrayList<>();
        this.knownWaypoints = new ArrayList<>();
    }

    public Waypoint homeAnchor() { return homeAnchor; }
    public double maxRadius() { return maxRadius; }
    public ExpeditionStatus status() { return status; }
    public ReturnReason returnReason() { return returnReason; }
    public List<Waypoint> breadcrumbs() { return Collections.unmodifiableList(breadcrumbs); }
    public List<Waypoint> knownWaypoints() { return Collections.unmodifiableList(knownWaypoints); }

    /** Sets off. */
    public void startExpedition() {
        this.status = ExpeditionStatus.EXPLORING_OUTWARD;
        this.returnReason = ReturnReason.NONE;
        this.breadcrumbs.clear();
    }

    /** Drops a breadcrumb on the way out, to follow back. */
    public void recordBreadcrumb(Waypoint breadcrumb) {
        this.breadcrumbs.add(breadcrumb);
    }

    /** Remembers a place it found. */
    public void addDiscoveredWaypoint(Waypoint waypoint) {
        if (!knownWaypoints.contains(waypoint)) {
            knownWaypoints.add(waypoint);
        }
    }

    /** Turns back home, and why. */
    public void triggerReturn(ReturnReason reason) {
        this.status = ExpeditionStatus.RETURNING;
        this.returnReason = reason;
    }

    /** Back home: the trip is over and the trail is cleared. */
    public void arriveAtBase() {
        this.status = ExpeditionStatus.IDLE_AT_BASE;
        this.returnReason = ReturnReason.NONE;
        this.breadcrumbs.clear();
    }

    /** The most recent breadcrumb, without taking it. */
    public Optional<Waypoint> lastBreadcrumb() {
        if (breadcrumbs.isEmpty()) return Optional.empty();
        return Optional.of(breadcrumbs.get(breadcrumbs.size() - 1));
    }

    /** Takes the most recent breadcrumb, following the trail back. */
    public Optional<Waypoint> popLastBreadcrumb() {
        if (breadcrumbs.isEmpty()) return Optional.empty();
        return Optional.of(breadcrumbs.remove(breadcrumbs.size() - 1));
    }
}
