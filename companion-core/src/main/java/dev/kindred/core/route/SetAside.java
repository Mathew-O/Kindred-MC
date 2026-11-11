package dev.kindred.core.route;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * In plain terms: the places a player has given up on for now, as a person gives up on ore it cannot get to: the
 * exact block for a while, and, when a walk to it found no way, the column round it too, until the player has moved
 * well away. Each failure at the same place gives it up for longer.
 *
 * Technical: The campaign's set-aside ledger (F-35e, ported from {@code PortalRunCampaign}'s {@code avoid},
 * {@code unreachable} and {@code blocked}): cells keyed by {@link Cell#asLong()}, each with the tick its setting aside
 * ends, and, for the column round an unreachable target, the feet it was given up from. Not thread-safe: one player's
 * decisions use it, on one thread.
 */
public final class SetAside {
    private final Map<Long, Long> blockedUntil = new HashMap<>();
    /** Columns round a target no walk reached: skipped until the back-off ends and the player has moved off. */
    private final Map<Long, Long> unreachableUntil = new HashMap<>();
    /** Where the player stood when each column was given up. */
    private final Map<Long, Cell> unreachableFrom = new HashMap<>();
    private final Map<Long, Integer> failures = new HashMap<>();

    /**
     * Skips a target that failed. Repeat failures at the same place back off longer, so a fast-ticking server
     * cannot make the player cycle through the same few unreachable targets: 2,400 ticks, doubled for each failure
     * there up to the sixth. With {@code column}, the cells round it too (a block each way and six up and down; with
     * {@code pool}, six each way and two up and down, the part of a pool round it), until the player at {@code feet}
     * has moved well away.
     */
    public void avoid(Cell pos, boolean column, boolean pool, long tick, Cell feet) {
        int count = failures.merge(pos.asLong(), 1, Integer::sum);
        long until = tick + (2400L << Math.min(count - 1, 5));
        blockedUntil.merge(pos.asLong(), until, Math::max); // The exact target, whatever kind it was.
        if (!column) return;
        int across = pool ? 6 : 1, up = pool ? 2 : 6;
        for (int dy = -up; dy <= up; dy++) for (int dx = -across; dx <= across; dx++) for (int dz = -across; dz <= across; dz++) {
            long key = pos.add(dx, dy, dz).asLong();
            unreachableUntil.merge(key, until, Math::max);
            unreachableFrom.put(key, feet);
        }
    }

    /** Skipped until its back-off ends and the player at {@code feet} has moved well away from where the walk failed. */
    public boolean unreachable(Cell pos, long now, Cell feet) {
        Long until = unreachableUntil.get(pos.asLong());
        if (until == null) return false;
        Cell from = unreachableFrom.get(pos.asLong());
        return until > now || from != null && from.squaredDistance(feet) < 8 * 8;
    }

    /** Whether the cell with key {@code key} is set aside at {@code now}. */
    public boolean blocked(long key, long now) {
        Long until = blockedUntil.get(key);
        return until != null && until > now;
    }

    /** The cell with key {@code key} set aside until {@code until} at least. */
    public void until(long key, long until) {
        blockedUntil.merge(key, until, Math::max);
    }

    /** The cell with key {@code key} set aside until {@code until}, whatever it was set aside until before. */
    public void put(long key, long until) {
        blockedUntil.put(key, until);
    }

    /** The cells set aside and until when, read-only: for a replay's copy of the goal's memory (F-27a). */
    public Map<Long, Long> blockedUntil() { return Collections.unmodifiableMap(blockedUntil); }

    /** The failures counted at each cell, read-only (F-27a). */
    public Map<Long, Integer> failures() { return Collections.unmodifiableMap(failures); }

    /** The columns' cells given up as unreachable and until when, read-only (F-27a). */
    public Map<Long, Long> unreachableUntil() { return Collections.unmodifiableMap(unreachableUntil); }

    /** Where the player stood when the cell with key {@code key} was given up as unreachable, or null (F-27a). */
    public Cell unreachableFrom(long key) { return unreachableFrom.get(key); }

    /** A replay's count of failures at the cell with key {@code key}, restored (F-27a). */
    public void failures(long key, int count) { failures.put(key, count); }

    /** A replay's unreachable cell restored (F-27a): until {@code until} at least, given up from {@code from} if known. */
    public void unreachable(long key, long until, Cell from) {
        unreachableUntil.merge(key, until, Math::max);
        if (from != null) unreachableFrom.put(key, from);
    }

    /** Everything set aside looked at afresh: the cells, the columns and the failure counts. */
    public void clear() {
        blockedUntil.clear();
        unreachableUntil.clear();
        unreachableFrom.clear();
        failures.clear();
    }
}
