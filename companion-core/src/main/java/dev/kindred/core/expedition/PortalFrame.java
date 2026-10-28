package dev.kindred.core.expedition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * In plain terms: checks whether a set of blocks is a proper Nether portal frame: obsidian all round a 2 by 3
 * opening (the corners are optional), standing upright along either axis.
 *
 * Technical: Geometric verification for standard Nether portal frames (E-15).
 * Validates 4x5 minimum bounding frame (2x3 opening, >= 10 obsidian blocks, 4 optional corners).
 */
public record PortalFrame(
        BlockPos3i interiorBottomLeft,
        int interiorWidth,
        int interiorHeight,
        Axis axis,
        int obsidianCount,
        boolean valid,
        String failureReason
) {
    public enum Axis {
        X, Z
    }

    public record BlockPos3i(int x, int y, int z) {
        public BlockPos3i offset(int dx, int dy, int dz) {
            return new BlockPos3i(x + dx, y + dy, z + dz);
        }
    }

    public static final String OBSIDIAN = "minecraft:obsidian";
    public static final String AIR = "minecraft:air";
    public static final String PORTAL = "minecraft:nether_portal";

    /**
     * Evaluates whether a valid Nether portal frame exists with the given interior opening dimensions
     * (standard 2 wide by 3 tall) and orientation.
     * Minimum 10 obsidian blocks required (corners are optional).
     */
    public static PortalFrame evaluate(BlockPos3i interiorBottomLeft, int interiorWidth, int interiorHeight,
                                       Axis axis, Function<BlockPos3i, String> blockProvider) {
        Objects.requireNonNull(interiorBottomLeft, "interiorBottomLeft");
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(blockProvider, "blockProvider");

        if (interiorWidth < 2 || interiorHeight < 3) {
            return new PortalFrame(interiorBottomLeft, interiorWidth, interiorHeight, axis, 0, false,
                    "Interior dimensions must be at least 2x3 (got " + interiorWidth + "x" + interiorHeight + ")");
        }

        int dx = axis == Axis.X ? 1 : 0;
        int dz = axis == Axis.Z ? 1 : 0;

        int obsidianCount = 0;

        // Check bottom row (interiorWidth blocks of obsidian)
        int baseY = interiorBottomLeft.y() - 1;
        for (int i = 0; i < interiorWidth; i++) {
            BlockPos3i pos = new BlockPos3i(
                    interiorBottomLeft.x() + i * dx,
                    baseY,
                    interiorBottomLeft.z() + i * dz
            );
            if (!isObsidian(blockProvider.apply(pos))) {
                return new PortalFrame(interiorBottomLeft, interiorWidth, interiorHeight, axis, obsidianCount, false,
                        "Missing obsidian at bottom frame position " + pos);
            }
            obsidianCount++;
        }

        // Check top row (interiorWidth blocks of obsidian)
        int topY = interiorBottomLeft.y() + interiorHeight;
        for (int i = 0; i < interiorWidth; i++) {
            BlockPos3i pos = new BlockPos3i(
                    interiorBottomLeft.x() + i * dx,
                    topY,
                    interiorBottomLeft.z() + i * dz
            );
            if (!isObsidian(blockProvider.apply(pos))) {
                return new PortalFrame(interiorBottomLeft, interiorWidth, interiorHeight, axis, obsidianCount, false,
                        "Missing obsidian at top frame position " + pos);
            }
            obsidianCount++;
        }

        // Check left column (interiorHeight blocks of obsidian)
        int leftX = interiorBottomLeft.x() - dx;
        int leftZ = interiorBottomLeft.z() - dz;
        for (int y = 0; y < interiorHeight; y++) {
            BlockPos3i pos = new BlockPos3i(leftX, interiorBottomLeft.y() + y, leftZ);
            if (!isObsidian(blockProvider.apply(pos))) {
                return new PortalFrame(interiorBottomLeft, interiorWidth, interiorHeight, axis, obsidianCount, false,
                        "Missing obsidian at left frame position " + pos);
            }
            obsidianCount++;
        }

        // Check right column (interiorHeight blocks of obsidian)
        int rightX = interiorBottomLeft.x() + interiorWidth * dx;
        int rightZ = interiorBottomLeft.z() + interiorWidth * dz;
        for (int y = 0; y < interiorHeight; y++) {
            BlockPos3i pos = new BlockPos3i(rightX, interiorBottomLeft.y() + y, rightZ);
            if (!isObsidian(blockProvider.apply(pos))) {
                return new PortalFrame(interiorBottomLeft, interiorWidth, interiorHeight, axis, obsidianCount, false,
                        "Missing obsidian at right frame position " + pos);
            }
            obsidianCount++;
        }

        // Check interior blocks (must be air or portal)
        for (int i = 0; i < interiorWidth; i++) {
            for (int y = 0; y < interiorHeight; y++) {
                BlockPos3i pos = new BlockPos3i(
                        interiorBottomLeft.x() + i * dx,
                        interiorBottomLeft.y() + y,
                        interiorBottomLeft.z() + i * dz
                );
                String block = blockProvider.apply(pos);
                if (!isInteriorPassable(block)) {
                    return new PortalFrame(interiorBottomLeft, interiorWidth, interiorHeight, axis, obsidianCount, false,
                            "Interior obstructed by non-portal block " + block + " at " + pos);
                }
            }
        }

        // Optional corner blocks count toward obsidian count if present
        BlockPos3i[] corners = new BlockPos3i[]{
                new BlockPos3i(leftX, baseY, leftZ),
                new BlockPos3i(rightX, baseY, rightZ),
                new BlockPos3i(leftX, topY, leftZ),
                new BlockPos3i(rightX, topY, rightZ)
        };
        for (BlockPos3i corner : corners) {
            if (isObsidian(blockProvider.apply(corner))) {
                obsidianCount++;
            }
        }

        return new PortalFrame(interiorBottomLeft, interiorWidth, interiorHeight, axis, obsidianCount, true, null);
    }

    /**
     * Finds a valid portal frame of standard size 2x3 around a given test block position.
     */
    public static Optional<PortalFrame> detect(BlockPos3i targetPos, Function<BlockPos3i, String> blockProvider) {
        // Try both axes and possible interior offsets (interior bottom-left can be targetPos or offset)
        for (Axis axis : Axis.values()) {
            int dx = axis == Axis.X ? 1 : 0;
            int dz = axis == Axis.Z ? 1 : 0;
            for (int ox = 0; ox <= 1; ox++) {
                for (int oy = 0; oy <= 2; oy++) {
                    BlockPos3i candidateBase = new BlockPos3i(
                            targetPos.x() - ox * dx,
                            targetPos.y() - oy,
                            targetPos.z() - ox * dz
                    );
                    PortalFrame frame = evaluate(candidateBase, 2, 3, axis, blockProvider);
                    if (frame.valid()) {
                        return Optional.of(frame);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The blocks inside the frame, where the portal itself appears. */
    public List<BlockPos3i> interiorPositions() {
        List<BlockPos3i> list = new ArrayList<>(interiorWidth * interiorHeight);
        int dx = axis == Axis.X ? 1 : 0;
        int dz = axis == Axis.Z ? 1 : 0;
        for (int i = 0; i < interiorWidth; i++) {
            for (int y = 0; y < interiorHeight; y++) {
                list.add(new BlockPos3i(
                        interiorBottomLeft.x() + i * dx,
                        interiorBottomLeft.y() + y,
                        interiorBottomLeft.z() + i * dz
                ));
            }
        }
        return list;
    }

    /** Whether a block is obsidian. */
    private static boolean isObsidian(String blockId) {
        return OBSIDIAN.equals(blockId);
    }

    /** Whether a block inside the frame is empty enough for a portal. */
    private static boolean isInteriorPassable(String blockId) {
        return blockId == null || AIR.equals(blockId) || "minecraft:cave_air".equals(blockId)
                || PORTAL.equals(blockId) || "minecraft:fire".equals(blockId);
    }
}
