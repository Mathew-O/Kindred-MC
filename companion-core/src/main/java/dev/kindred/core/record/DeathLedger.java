package dev.kindred.core.record;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In plain terms: how many times each player has died, from the deaths in the World Record.
 *
 * Technical: The death ledger (F-31). A {@code death} event names the one who died as its {@code actor}; its cause is in
 * {@code data.cause} and its killer, when a player, in {@code data.killer}. Power (M-06c) reads the same events.
 */
public final class DeathLedger implements Ledger<Map<UUID, Integer>> {
    public static final String DEATH = "death";
    public static final DeathLedger INSTANCE = new DeathLedger();

    @Override public String name() { return "deaths"; }

    @Override public Set<String> kinds() { return Set.of(DEATH); }

    @Override public Map<UUID, Integer> empty() { return Map.of(); }

    @Override
    public Map<UUID, Integer> apply(Map<UUID, Integer> state, WorldEvent event) {
        if (!DEATH.equals(event.kind()) || event.actor() == null) return state;
        var next = new TreeMap<>(state);
        next.merge(event.actor(), 1, Integer::sum);
        return Collections.unmodifiableMap(next);
    }
}
