package dev.kindred.core.hearing;

import java.util.ArrayDeque;
import java.util.List;

/**
 * In plain terms: what a player has heard and not yet thought about, the newest few dozen; older sounds fall away.
 *
 * Technical: a bounded queue of {@link HeardSound}, at most {@link #CAPACITY}, the oldest dropped first. Filled as sounds
 * arrive and drained by the observation builder; cleared on a stop. One thread only (the server's); O(1) to offer.
 */
public final class Earshot {
    public static final int CAPACITY = 64;

    private final ArrayDeque<HeardSound> heard = new ArrayDeque<>(CAPACITY);
    private long dropped;

    /** A sound heard; the oldest goes when full. */
    public void offer(HeardSound sound) {
        if (heard.size() == CAPACITY) {
            heard.removeFirst();
            dropped++;
        }
        heard.addLast(sound);
    }

    /** Everything heard since the last drain, oldest first, and empty after. */
    public List<HeardSound> drain() {
        if (heard.isEmpty()) return List.of();
        var out = List.copyOf(heard);
        heard.clear();
        return out;
    }

    /** Forgets everything not yet drained (a stop). */
    public void clear() { heard.clear(); }

    public int size() { return heard.size(); }

    /** Sounds that fell away unheard since the start, the queue being full. */
    public long dropped() { return dropped; }
}
