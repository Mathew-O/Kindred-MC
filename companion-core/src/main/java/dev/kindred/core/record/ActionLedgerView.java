package dev.kindred.core.record;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In plain terms: where each recorded action stands (prepared, dispatched, verified, failed, cancelled, uncertain),
 * worked out from the World Record, so it can be checked against the action ledger's own table.
 *
 * Technical: The action ledger as a kind of event (F-31). Every insert and transition of {@code action_ledger} writes
 * an {@code action} event in the same transaction: {@code subject} the action id, {@code actor} the player,
 * {@code data.state} and {@code data.revision}. The last event for an action is its row.
 */
public final class ActionLedgerView implements Ledger<Map<UUID, ActionLedgerView.Row>> {
    public static final String ACTION = "action";
    public static final ActionLedgerView INSTANCE = new ActionLedgerView();

    /** An action's state and revision. */
    public record Row(String state, long revision) {}

    @Override public String name() { return "actions"; }

    @Override public Set<String> kinds() { return Set.of(ACTION); }

    @Override public Map<UUID, Row> empty() { return Map.of(); }

    @Override
    public Map<UUID, Row> apply(Map<UUID, Row> state, WorldEvent event) {
        UUID action;
        long revision;
        try {
            action = UUID.fromString(event.subject());
            revision = Long.parseLong(event.get("revision", ""));
        } catch (IllegalArgumentException malformed) {
            return state;
        }
        var next = new TreeMap<>(state);
        next.put(action, new Row(event.get("state", ""), revision));
        return Collections.unmodifiableMap(next);
    }
}
