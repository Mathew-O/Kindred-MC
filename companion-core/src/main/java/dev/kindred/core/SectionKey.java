package dev.kindred.core;

/**
 * A 16x16x16 chunk section in section coordinates. Perception plans, budgets and freshness are tracked per
 * section because the native palette lets a whole section be skipped without touching its 4,096 blocks.
 */
public record SectionKey(int x, int y, int z) {
    public static SectionKey of(WorkSetup.BlockPoint point) {
        return new SectionKey(point.x() >> 4, point.y() >> 4, point.z() >> 4);
    }

    public int minBlockX() { return x << 4; }
    public int minBlockY() { return y << 4; }
    public int minBlockZ() { return z << 4; }

    public boolean contains(WorkSetup.BlockPoint point) {
        return (point.x() >> 4) == x && (point.y() >> 4) == y && (point.z() >> 4) == z;
    }

    /** Squared distance between section centers, in sections. */
    public long squaredDistance(SectionKey other) {
        long dx = x - other.x;
        long dy = y - other.y;
        long dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() { return x + "," + y + "," + z; }
}
