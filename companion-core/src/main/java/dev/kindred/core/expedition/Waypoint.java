package dev.kindred.core.expedition;

import java.util.Objects;

/**
 * In plain terms: a remembered place, such as home, a shelter, a resource patch, a workstation, a danger, a
 * landmark, a breadcrumb or a Nether portal, with its coordinates, dimension and when it was noted. Includes
 * the 8-to-1 conversion between Overworld and Nether coordinates.
 *
 * Technical: Immutable spatial coordinate and landmark observation.
 */
public record Waypoint(
        String name,
        int x,
        int y,
        int z,
        String dimension,
        WaypointKind kind,
        long recordedTick
) {
    public Waypoint {
        Objects.requireNonNull(name, "Waypoint name cannot be null");
        Objects.requireNonNull(dimension, "Dimension cannot be null");
        Objects.requireNonNull(kind, "WaypointKind cannot be null");
    }

    /** Straight-line distance to a point. */
    public double distanceTo(int ox, int oy, int oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Distance to a point ignoring height. */
    public double horizontalDistanceTo(int ox, int oz) {
        double dx = x - ox;
        double dz = z - oz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** An Overworld coordinate in the Nether (divided by 8). */
    public static int overworldToNether(int overworldCoord) {
        return Math.floorDiv(overworldCoord, 8);
    }

    /** A Nether coordinate in the Overworld (times 8). */
    public static int netherToOverworld(int netherCoord) {
        return Math.multiplyExact(netherCoord, 8);
    }

    /** Where this place lines up in the Nether. */
    public Waypoint toNetherCoordinates(String newName) {
        if ("minecraft:the_nether".equals(dimension)) {
            return this;
        }
        return new Waypoint(newName != null ? newName : name + " (Nether)",
                overworldToNether(x), y, overworldToNether(z),
                "minecraft:the_nether", kind, recordedTick);
    }

    /** Where this place lines up in the Overworld. */
    public Waypoint toOverworldCoordinates(String newName) {
        if ("minecraft:overworld".equals(dimension)) {
            return this;
        }
        return new Waypoint(newName != null ? newName : name + " (Overworld)",
                netherToOverworld(x), y, netherToOverworld(z),
                "minecraft:overworld", kind, recordedTick);
    }

    /** A new remembered place. */
    public static Waypoint of(String name, int x, int y, int z, String dimension, WaypointKind kind, long recordedTick) {
        return new Waypoint(name, x, y, z, dimension, kind, recordedTick);
    }
}
