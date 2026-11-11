package dev.kindred.core.route;

import java.util.Arrays;

/**
 * What one search has worked out about each cell, by the cell's key: an open-addressed table of longs to doubles, so
 * the many lookups a search makes neither box nor allocate.
 */
final class CellMemo {
    private long[] keys = new long[256];
    private double[] values = new double[256];
    private boolean[] used = new boolean[256];
    private int size;

    /** Whether a value is kept for the key. */
    boolean contains(long key) {
        return used[slot(key)];
    }

    /** The value kept for the key, or {@code absent} when there is none. */
    double get(long key, double absent) {
        int slot = slot(key);
        return used[slot] ? values[slot] : absent;
    }

    /** Keeps the value for the key. */
    void put(long key, double value) {
        int slot = slot(key);
        if (!used[slot]) {
            if (++size * 2 > keys.length) {
                grow();
                slot = slot(key);
            }
            used[slot] = true;
            keys[slot] = key;
        }
        values[slot] = value;
    }

    /** Forgets every value. */
    void clear() {
        if (size == 0) return;
        Arrays.fill(used, false);
        size = 0;
    }

    private int slot(long key) {
        int mask = keys.length - 1;
        int slot = (int) (mix(key) & mask);
        while (used[slot] && keys[slot] != key) slot = (slot + 1) & mask;
        return slot;
    }

    private static long mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 32);
    }

    private void grow() {
        long[] oldKeys = keys;
        double[] oldValues = values;
        boolean[] oldUsed = used;
        keys = new long[oldKeys.length * 2];
        values = new double[oldKeys.length * 2];
        used = new boolean[oldKeys.length * 2];
        for (int i = 0; i < oldKeys.length; i++) {
            if (!oldUsed[i]) continue;
            int slot = slot(oldKeys[i]);
            used[slot] = true;
            keys[slot] = oldKeys[i];
            values[slot] = oldValues[i];
        }
    }
}
