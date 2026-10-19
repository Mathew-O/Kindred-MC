package dev.kindred.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Saved bounded work permissions. Home/storage are optional metadata; each native task determines its
 * actual prerequisites. Legacy chores retain missing()/readyForChores(). Incomplete setup never grants permission.
 */
public record WorkSetup(UUID companionId, String dimension, HomeMarker home, WorkRegion area, StorageTarget storage,
                        ResourcePatch patch, long revision) {
    public static final int MAX_AREA_CHUNKS = 9;
    public static final int MAX_PATCH_BLOCKS = 4_096;

    public enum Field { HOME, AREA, STORAGE, PATCH }

    public record BlockPoint(int x, int y, int z) {
        public int chunkX() { return x >> 4; }
        public int chunkZ() { return z >> 4; }
    }

    public record HomeMarker(BlockPoint position) {
        public HomeMarker { Objects.requireNonNull(position); }
    }

    /** Horizontal block bounds (inclusive); the chunk footprint is what admission later counts. */
    public record WorkRegion(int minX, int minZ, int maxX, int maxZ) {
        public WorkRegion {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Region bounds are inverted");
            long chunks = ((long) (maxX >> 4) - (minX >> 4) + 1) * ((long) (maxZ >> 4) - (minZ >> 4) + 1);
            if (chunks > MAX_AREA_CHUNKS) {
                throw new IllegalArgumentException("Work area touches " + chunks + " chunks; the limit is " + MAX_AREA_CHUNKS);
            }
        }

        public int chunkCount() { return ((maxX >> 4) - (minX >> 4) + 1) * ((maxZ >> 4) - (minZ >> 4) + 1); }

        public boolean contains(BlockPoint point) {
            return point.x() >= minX && point.x() <= maxX && point.z() >= minZ && point.z() <= maxZ;
        }

        /** The default three-by-three chunk neighborhood around a point. */
        public static WorkRegion around(BlockPoint point) {
            int cx = point.chunkX();
            int cz = point.chunkZ();
            return new WorkRegion((cx - 1) << 4, (cz - 1) << 4, ((cx + 2) << 4) - 1, ((cz + 2) << 4) - 1);
        }
    }

    /**
     * A container is identified by position, block identity and the version observed when it was assigned,
     * so a replaced or changed block is detected before any transfer rather than trusted by coordinates.
     */
    public record StorageTarget(BlockPoint position, String blockId, long observedVersion) {
        public StorageTarget {
            Objects.requireNonNull(position);
            if (blockId == null || blockId.isBlank()) throw new IllegalArgumentException("Storage needs a block identity");
            if (observedVersion < 0) throw new IllegalArgumentException("Observed version cannot be negative");
        }
    }

    /** The only place a gathering skill may break the named resource; a matching block elsewhere is never permission. */
    public record ResourcePatch(BlockPoint min, BlockPoint max, String resourceId) {
        public ResourcePatch {
            Objects.requireNonNull(min);
            Objects.requireNonNull(max);
            if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("Patch needs a resource identity");
            if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) throw new IllegalArgumentException("Patch bounds are inverted");
            long width = (long) max.x() - min.x() + 1;
            long height = (long) max.y() - min.y() + 1;
            long depth = (long) max.z() - min.z() + 1;
            if (width > MAX_PATCH_BLOCKS || height > MAX_PATCH_BLOCKS || depth > MAX_PATCH_BLOCKS) {
                throw new IllegalArgumentException("Patch dimensions exceed the " + MAX_PATCH_BLOCKS + " block volume limit");
            }
            long volume = width * height * depth; // Each factor is now bounded; this product cannot overflow.
            if (volume > MAX_PATCH_BLOCKS) throw new IllegalArgumentException("Patch spans " + volume + " blocks; the limit is " + MAX_PATCH_BLOCKS);
        }

        public boolean contains(BlockPoint p) {
            return p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y() && p.z() >= min.z() && p.z() <= max.z();
        }
    }

    public WorkSetup {
        Objects.requireNonNull(companionId, "companionId");
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Setup needs the companion's dimension");
        if (revision < 0) throw new IllegalArgumentException("Negative setup revision");
        if (area != null) {
            if (home != null && !area.contains(home.position())) throw new IllegalArgumentException("Home must lie inside the work area");
            if (storage != null && !area.contains(storage.position())) throw new IllegalArgumentException("Storage must lie inside the work area");
            if (patch != null && (!area.contains(patch.min()) || !area.contains(patch.max()))) {
                throw new IllegalArgumentException("The resource patch must lie inside the work area");
            }
        }
    }

    public static WorkSetup empty(UUID companionId, String dimension) {
        return new WorkSetup(companionId, dimension, null, null, null, null, 0);
    }

    public WorkSetup withHome(HomeMarker marker) {
        return new WorkSetup(companionId, dimension, marker, area, storage, patch, Math.incrementExact(revision));
    }

    public WorkSetup withArea(WorkRegion region) {
        return new WorkSetup(companionId, dimension, home, region, storage, patch, Math.incrementExact(revision));
    }

    public WorkSetup withStorage(StorageTarget target) {
        return new WorkSetup(companionId, dimension, home, area, target, patch, Math.incrementExact(revision));
    }

    public WorkSetup withPatch(ResourcePatch resource) {
        return new WorkSetup(companionId, dimension, home, area, storage, resource, Math.incrementExact(revision));
    }

    /** Fields the first autonomous gathering/deposit chore needs; the patch is required only for gathering. */
    public List<Field> missing() {
        List<Field> missing = new ArrayList<>();
        if (home == null) missing.add(Field.HOME);
        if (area == null) missing.add(Field.AREA);
        if (storage == null) missing.add(Field.STORAGE);
        if (patch == null) missing.add(Field.PATCH);
        return List.copyOf(missing);
    }

    public boolean readyForChores() { return missing().isEmpty(); }

    public static String explain(Field field) {
        return switch (field) {
            case HOME -> "no home marker: stand where it should live and use /companion home set";
            case AREA -> "no work area: use /companion area set <x1> <z1> <x2> <z2> (at most nine chunks) or /companion area default";
            case STORAGE -> "no assigned storage: look at a chest inside the area and use /companion storage set";
            case PATCH -> "no resource patch: an operator or owner assigns it with /companion patch set <x1> <y1> <z1> <x2> <y2> <z2> <block>";
        };
    }

    public String explanation() {
        List<Field> missing = missing();
        if (missing.isEmpty()) return "Setup complete for the first chore; autonomous work still needs its own gates.";
        StringBuilder text = new StringBuilder("Setup incomplete: ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) text.append("; ");
            text.append(explain(missing.get(i)));
        }
        return text.append('.').toString();
    }
}
