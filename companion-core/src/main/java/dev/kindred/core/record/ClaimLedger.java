package dev.kindred.core.record;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * In plain terms: which faction owns each claimed chunk, worked out from the claims, releases and captures in the
 * World Record, so a restart loses no land.
 *
 * Technical: The territory ledger (F-31; its rules are M-06d's and M-06e's). Events carry the chunk as {@code place}
 * ({@code dimension:chunkX:chunkZ}) and the owning faction as {@code subject}; a {@code capture} carries the loser as
 * {@code from}. Only facts the rules already accepted are written, so the fold does not judge them; a release by a
 * faction that does not own the chunk changes nothing.
 */
public final class ClaimLedger implements Ledger<Map<String, String>> {
    public static final String CLAIM = "claim", UNCLAIM = "unclaim", CAPTURE = "capture";
    public static final ClaimLedger INSTANCE = new ClaimLedger();

    @Override public String name() { return "claims"; }

    @Override public Set<String> kinds() { return Set.of(CLAIM, UNCLAIM, CAPTURE); }

    @Override public Map<String, String> empty() { return Map.of(); }

    @Override
    public Map<String, String> apply(Map<String, String> state, WorldEvent event) {
        if (event.place().isEmpty() || event.subject().isEmpty()) return state;
        var next = new TreeMap<>(state);
        switch (event.kind()) {
            case CLAIM, CAPTURE -> next.put(event.place(), event.subject());
            case UNCLAIM -> next.remove(event.place(), event.subject());
            default -> { return state; }
        }
        return Collections.unmodifiableMap(next);
    }

    /** A chunk's key as events carry it. */
    public static String chunk(String dimension, int chunkX, int chunkZ) { return dimension + ":" + chunkX + ":" + chunkZ; }
}
