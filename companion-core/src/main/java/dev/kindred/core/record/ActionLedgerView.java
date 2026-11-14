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
 * {@code data.state} and {@code data.revision}. The last event for an action is its row. A settled action's history
 * older than three game days is compacted by the store's action fold into one {@link #FOLDED} event carrying the same
 * final row (and {@code folded}, the count it replaces), read here as the action's last event.
 */
public final class ActionLedgerView implements Ledger<Map<UUID, ActionLedgerView.Row>> {
    public static final String ACTION = "action";
    /** A journal line, written to the Record as an event. */
    public static final String JOURNAL = "journal";
    /** A settled action's history compacted into its final row (the action fold): not news, its id fresh. */
    public static final String FOLDED = "action.folded";

    /** Whether an action in {@code state} is settled: verified, failed or cancelled. */
    public static boolean settled(String state) {
        return "VERIFIED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }
    public static final ActionLedgerView INSTANCE = new ActionLedgerView();

    /** An action's state and revision. */
    public record Row(String state, long revision) {}

    @Override public String name() { return "actions"; }

    @Override public Set<String> kinds() { return Set.of(ACTION, FOLDED); }

    @Override public Map<UUID, Row> empty() { return Map.of(); }

    @Override
    public Map<UUID, Row> apply(Map<UUID, Row> state, WorldEvent event) {
        var next = new TreeMap<>(state);
        put(next, event);
        return Collections.unmodifiableMap(next);
    }

    /**
     * The ledger rebuilt from {@code events}: the same as applying each in turn, folded into one map rather than a copy
     * of the whole ledger for every event (F-31d). Copying made the agreement check grow with actions times events, the
     * writer held for 15 seconds by the sixth check of an evaluation and the server's stop kept waiting behind it.
     */
    @Override
    public Map<UUID, Row> replay(Iterable<WorldEvent> events) {
        var state = new TreeMap<UUID, Row>();
        for (WorldEvent event : events) if (kinds().contains(event.kind())) put(state, event);
        return Collections.unmodifiableMap(state);
    }

    /** An event's row put in its action's place; a malformed one changes nothing. */
    private static void put(TreeMap<UUID, Row> state, WorldEvent event) {
        UUID action;
        long revision;
        try {
            action = UUID.fromString(event.subject());
            revision = Long.parseLong(event.get("revision", ""));
        } catch (IllegalArgumentException malformed) {
            return;
        }
        state.put(action, new Row(event.get("state", ""), revision));
    }
}
