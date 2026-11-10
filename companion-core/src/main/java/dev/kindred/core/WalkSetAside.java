package dev.kindred.core;

import java.util.HashMap;
import java.util.Map;

/**
 * In plain terms: a place a player has been refused a walk to, again and again from about the same spot, is let be for
 * ten minutes, whatever routine asks for it, so that one request never becomes a thousand. Moving away, or changing the
 * ground with its own hands, gives the place another try.
 *
 * Technical: LOOPS 1 / M-01f slice 2. Pure, confined to the caller's (the server's) thread. A target is a key of the
 * adapter's choosing (a four-block cell of the walk's target, so that neighbouring blocks of one patch count together); "from" is where the player stood when it was refused. A target is set aside after
 * {@link #REFUSALS} refusals within {@link #NEAR} blocks of the first, for {@link #ASIDE_TICKS} ticks, and only for
 * a player still within {@link #NEAR} blocks of where it was refused. At most {@link #MAX} targets are kept.
 */
public final class WalkSetAside {
    /** Refusals of one target, from around one place, that set it aside. */
    public static final int REFUSALS = 3;
    /** Ten minutes of game time. */
    public static final long ASIDE_TICKS = 12_000;
    /** How near "from around the same place" is, in blocks. */
    public static final int NEAR = 8;
    /** The most targets kept; the oldest give way. */
    public static final int MAX = 256;

    private record Entry(int fromX, int fromY, int fromZ, int refusals, long firstTick, long until) { }

    private final Map<Long, Entry> targets = new HashMap<>();

    /** A walk to {@code target} refused at {@code tick}, the player standing at {@code x, y, z}. True if it is now set aside. */
    public boolean refused(long target, int x, int y, int z, long tick) {
        Entry known = targets.get(target);
        if (known == null || !near(known, x, y, z) || tick - known.firstTick() > ASIDE_TICKS) {
            if (targets.size() >= MAX && known == null) evictOldest();
            known = new Entry(x, y, z, 0, tick, 0);
        }
        int refusals = known.refusals() + 1;
        long until = refusals >= REFUSALS ? tick + ASIDE_TICKS : known.until();
        targets.put(target, new Entry(known.fromX(), known.fromY(), known.fromZ(), refusals, known.firstTick(), until));
        return until > tick;
    }

    /** Whether a walk to {@code target} is set aside for a player standing at {@code x, y, z} at {@code tick}. */
    public boolean aside(long target, int x, int y, int z, long tick) {
        Entry known = targets.get(target);
        return known != null && known.until() > tick && near(known, x, y, z);
    }

    /** A walk to {@code target} that arrived: its refusals are forgotten. */
    public void walked(long target) {
        targets.remove(target);
    }

    /** The player changed the ground with its own hands (a break or a placement): every place gets another try. */
    public void groundChanged() {
        targets.clear();
    }

    /** Targets kept, for tests and the status line. */
    public int size() {
        return targets.size();
    }

    private static boolean near(Entry e, int x, int y, int z) {
        long dx = x - e.fromX(), dy = y - e.fromY(), dz = z - e.fromZ();
        return dx * dx + dy * dy + dz * dz <= (long) NEAR * NEAR;
    }

    private void evictOldest() {
        Long oldest = null;
        long tick = Long.MAX_VALUE;
        for (var e : targets.entrySet()) {
            if (e.getValue().firstTick() < tick) {
                tick = e.getValue().firstTick();
                oldest = e.getKey();
            }
        }
        if (oldest != null) targets.remove(oldest);
    }
}
