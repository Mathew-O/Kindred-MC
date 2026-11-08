package dev.kindred.core.record;

import java.util.Objects;
import java.util.Set;

/**
 * In plain terms: an exact fact kept from the World Record and nothing else (who owns a chunk, a match's result, how
 * many times a player died), which can always be worked out again from the Record alone.
 *
 * Technical: A pure fold over events (F-31). {@link #apply} must ignore kinds it does not name and never throw on a
 * well-formed event of its kinds, so a replay of the whole Record is always possible. Rules read ledgers; nothing but a
 * replay writes one.
 */
public interface Ledger<S> {
    /** Its name, for the agreement check's report. */
    String name();

    /** The event kinds it reads. */
    Set<String> kinds();

    /** Its state before any event. */
    S empty();

    /** Its state after {@code event}; the same state for a kind it does not read. */
    S apply(S state, WorldEvent event);

    /** Its state rebuilt from {@code events}, in the Record's order. */
    default S replay(Iterable<WorldEvent> events) {
        S state = empty();
        for (WorldEvent event : events) if (kinds().contains(event.kind())) state = Objects.requireNonNull(apply(state, event));
        return state;
    }

    /** The agreement check: whether {@code kept} is exactly what the Record gives. */
    default Agreement<S> check(Iterable<WorldEvent> events, S kept) {
        S replayed = replay(events);
        return new Agreement<>(name(), replayed.equals(kept), replayed, kept);
    }

    /** One ledger checked against the Record: whether they agree, and both states for the report. */
    record Agreement<S>(String ledger, boolean agrees, S fromRecord, S kept) {}
}
