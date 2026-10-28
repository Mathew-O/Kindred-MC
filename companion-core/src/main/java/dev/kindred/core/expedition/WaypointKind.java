package dev.kindred.core.expedition;

/**
 * In plain terms: the kinds of places a player remembers: its home bed, shelters, resource patches,
 * workstations, dangers, landmarks, breadcrumbs, Nether portals and fortresses.
 *
 * Technical: Categorization of waypoints discovered or navigated during exploration and expeditions.
 */
public enum WaypointKind {
    HOME_BED,
    SHELTER,
    RESOURCE_PATCH,
    WORKSTATION,
    HAZARD,
    LANDMARK,
    BREADCRUMB,
    NETHER_PORTAL,
    NETHER_FORTRESS
}
