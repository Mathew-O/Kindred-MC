package dev.kindred.design.model;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable pure values. No Minecraft types, IO, inventory, or body authority. */
public final class DesignModel {
    private DesignModel() {}

    public static final int MAX_CELLS = 65_536;
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9_.:-]{1,96}");

    public static String id(String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid bounded identifier: " + value);
        }
        return value;
    }

    public record Pos(int x, int y, int z) implements Comparable<Pos> {
        public Pos add(int dx, int dy, int dz) {
            return new Pos(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
        }
        public Pos below() { return add(0, -1, 0); }
        public Pos above() { return add(0, 1, 0); }
        public List<Pos> horizontalNeighbors() {
            return List.of(add(1, 0, 0), add(-1, 0, 0), add(0, 0, 1), add(0, 0, -1));
        }
        @Override public int compareTo(Pos other) {
            int c = Integer.compare(y, other.y);
            if (c == 0) c = Integer.compare(z, other.z);
            return c == 0 ? Integer.compare(x, other.x) : c;
        }
        public String key() { return x + "," + y + "," + z; }
    }

    /** Inclusive bounds, with overflow-safe bounded volume. */
    public record Box(Pos min, Pos max) {
        public Box {
            Objects.requireNonNull(min);
            Objects.requireNonNull(max);
            if (min.x > max.x || min.y > max.y || min.z > max.z) {
                throw new IllegalArgumentException("Inverted bounds");
            }
            long volume = 1;
            for (long length : new long[]{(long) max.x - min.x + 1,
                    (long) max.y - min.y + 1, (long) max.z - min.z + 1}) {
                if (length > MAX_CELLS || volume > MAX_CELLS / length) {
                    throw new IllegalArgumentException("Bounds exceed " + MAX_CELLS + " cells");
                }
                volume *= length;
            }
        }
        public boolean contains(Pos p) {
            return p.x >= min.x && p.x <= max.x && p.y >= min.y && p.y <= max.y
                    && p.z >= min.z && p.z <= max.z;
        }
        public List<Pos> cells() {
            List<Pos> result = new ArrayList<>();
            for (long y = min.y; y <= max.y; y++) {
                for (long z = min.z; z <= max.z; z++) {
                    for (long x = min.x; x <= max.x; x++) {
                        result.add(new Pos((int) x, (int) y, (int) z));
                    }
                }
            }
            return List.copyOf(result);
        }
    }

    /** Deliberately tiny pilot vocabulary. This is not a native registry validator. */
    public enum Material {
        COBBLESTONE("minecraft:cobblestone"), OAK_PLANKS("minecraft:oak_planks");
        private final String itemId;
        Material(String itemId) { this.itemId = itemId; }
        public String itemId() { return itemId; }
    }

    public enum Cell { AIR, SOLID, HAZARD }
    public enum Phase { FLOOR_AND_PATH, WALLS, ROOF }
    public enum DesignStatus { DRAFT, VALIDATED, ACCEPTED, SUPERSEDED }
    public enum ExecutionStatus {
        UNSCHEDULED, WAITING_RESOURCES, READY, BUILDING, VERIFYING, COMPLETED,
        PAUSED, BLOCKED, RECONCILING, CANCELLED
    }

    public record Observation(String observationId, UUID worldId, String dimension,
                              long revision, Instant capturedAt, String provenance,
                              Box bounds, Map<Pos, Cell> cells, Set<Pos> protectedCells) {
        public Observation {
            observationId = id(observationId);
            Objects.requireNonNull(worldId);
            dimension = id(dimension);
            if (revision < 0) throw new IllegalArgumentException("Negative revision");
            Objects.requireNonNull(capturedAt);
            provenance = id(provenance);
            Objects.requireNonNull(bounds);
            cells = Map.copyOf(cells);
            protectedCells = Set.copyOf(protectedCells);
            if (cells.size() > MAX_CELLS || !cells.keySet().stream().allMatch(bounds::contains)
                    || !protectedCells.stream().allMatch(bounds::contains)) {
                throw new IllegalArgumentException("Observation outside bounded scope");
            }
        }
        public Optional<Cell> cell(Pos p) { return Optional.ofNullable(cells.get(p)); }
    }

    public record Brief(String projectId, UUID worldId, String dimension, Box site,
                        Pos floorOrigin, Pos accessAnchor, int rotation, long seed,
                        int maxPlacements) {
        public Brief {
            projectId = id(projectId);
            Objects.requireNonNull(worldId);
            dimension = id(dimension);
            Objects.requireNonNull(site);
            Objects.requireNonNull(floorOrigin);
            Objects.requireNonNull(accessAnchor);
            if (rotation < 0 || rotation > 3) throw new IllegalArgumentException("Rotation must be 0..3");
            if (maxPlacements < 1 || maxPlacements > 4096) {
                throw new IllegalArgumentException("Pilot placement limit must be 1..4096");
            }
            if (!site.contains(floorOrigin) || !site.contains(accessAnchor)) {
                throw new IllegalArgumentException("Origin/anchor outside site");
            }
        }
    }

    public record Voxel(Pos position, Material material, Phase phase) {
        public Voxel {
            Objects.requireNonNull(position);
            Objects.requireNonNull(material);
            Objects.requireNonNull(phase);
        }
    }

    public record Port(String portId, Pos feet) {
        public Port { portId = id(portId); Objects.requireNonNull(feet); }
    }

    public record Design(String designId, long revision, String observationId,
                         long observationRevision, String generatorVersion, Brief brief,
                         List<Voxel> voxels, Set<Pos> requiredAir, List<Port> ports) {
        public Design {
            designId = id(designId);
            observationId = id(observationId);
            generatorVersion = id(generatorVersion);
            if (revision < 1 || observationRevision < 0) throw new IllegalArgumentException("Invalid revision");
            Objects.requireNonNull(brief);
            voxels = List.copyOf(voxels);
            requiredAir = Set.copyOf(requiredAir);
            ports = List.copyOf(ports);
            if (voxels.isEmpty() || voxels.size() > brief.maxPlacements || requiredAir.size() > MAX_CELLS
                    || ports.isEmpty() || ports.size() > 16) {
                throw new IllegalArgumentException("Invalid pilot design size");
            }
        }
    }
}
