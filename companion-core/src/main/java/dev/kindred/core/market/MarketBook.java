package dev.kindred.core.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * In plain terms: where each raw material's price stands: how much more (or less) of it the market holds than its
 * balance, when that last changed, and the price an hour ago it may not stray far from; plus the operator's own
 * baselines.
 *
 * Technical: The market ledger's state (M-06g). {@code stock} is in units of the root, positive when oversupplied; it
 * decays toward zero by its volatility's half-life. {@code anchor} is the mid price at {@code anchorMs}, renewed an hour
 * on; the mid may move at most {@link Pricing#MAX_HOURLY} from it within the hour. {@code history} keeps each root's
 * states of the last two days, for the 24-hour change.
 */
public record MarketBook(Map<String, State> states, Map<String, MarketTable.Root> baselines, Map<String, List<State>> history) {
    public static final MarketBook EMPTY = new MarketBook(Map.of(), Map.of(), Map.of());
    static final long KEPT_MS = 48L * 3_600_000;

    /** A root's stock and anchor at a moment. */
    public record State(double stock, long updatedMs, double anchor, long anchorMs) {
        public static final State FRESH = new State(0, 0, 0, 0);
    }

    public MarketBook {
        states = Collections.unmodifiableMap(new TreeMap<>(states));
        baselines = Collections.unmodifiableMap(new TreeMap<>(baselines));
        var copy = new TreeMap<String, List<State>>();
        history.forEach((root, list) -> copy.put(root, List.copyOf(list)));
        history = Collections.unmodifiableMap(copy);
    }

    public State state(String root) { return states.getOrDefault(root, State.FRESH); }

    public MarketBook with(Map<String, State> changed) {
        var next = new TreeMap<>(states);
        next.putAll(changed);
        var kept = new TreeMap<>(history);
        for (var entry : changed.entrySet()) {
            var list = new ArrayList<>(kept.getOrDefault(entry.getKey(), List.of()));
            list.add(entry.getValue());
            long cut = entry.getValue().updatedMs() - KEPT_MS;
            list.removeIf(s -> s.updatedMs() < cut);
            kept.put(entry.getKey(), list);
        }
        return new MarketBook(next, baselines, kept);
    }

    MarketBook withBaseline(MarketTable.Root root) {
        var next = new TreeMap<>(baselines);
        next.put(root.item(), root);
        return new MarketBook(states, next, history);
    }

    /** The root's state as it stood at {@code ms}: its last change before then, or fresh. */
    public State stateAt(String root, long ms) {
        State found = State.FRESH;
        for (State s : history.getOrDefault(root, List.of())) if (s.updatedMs() <= ms) found = s;
        return found;
    }
}
