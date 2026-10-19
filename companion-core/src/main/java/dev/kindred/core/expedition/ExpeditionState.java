package dev.kindred.core.expedition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * State machine managing an AI player's active expedition, home anchor, breadcrumbs, and discovered waypoints.
 */
public final class ExpeditionState {
    private final Waypoint homeAnchor;
    private final double maxRadius;
    private ExpeditionStatus status;
    private ReturnReason returnReason;
    private final List<Waypoint> breadcrumbs;
    private final List<Waypoint> knownWaypoints;

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

    public void startExpedition() {
        this.status = ExpeditionStatus.EXPLORING_OUTWARD;
        this.returnReason = ReturnReason.NONE;
        this.breadcrumbs.clear();
    }

    public void recordBreadcrumb(Waypoint breadcrumb) {
        this.breadcrumbs.add(breadcrumb);
    }

    public void addDiscoveredWaypoint(Waypoint waypoint) {
        if (!knownWaypoints.contains(waypoint)) {
            knownWaypoints.add(waypoint);
        }
    }

    public void triggerReturn(ReturnReason reason) {
        this.status = ExpeditionStatus.RETURNING;
        this.returnReason = reason;
    }

    public void arriveAtBase() {
        this.status = ExpeditionStatus.IDLE_AT_BASE;
        this.returnReason = ReturnReason.NONE;
        this.breadcrumbs.clear();
    }

    public Optional<Waypoint> lastBreadcrumb() {
        if (breadcrumbs.isEmpty()) return Optional.empty();
        return Optional.of(breadcrumbs.get(breadcrumbs.size() - 1));
    }

    public Optional<Waypoint> popLastBreadcrumb() {
        if (breadcrumbs.isEmpty()) return Optional.empty();
        return Optional.of(breadcrumbs.remove(breadcrumbs.size() - 1));
    }
}
