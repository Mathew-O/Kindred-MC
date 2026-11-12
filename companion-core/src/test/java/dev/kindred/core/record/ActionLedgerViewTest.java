package dev.kindred.core.record;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: the action ledger rebuilt from the World Record in one pass is exactly what it was when rebuilt an
 * event at a time, with repeated, malformed, folded and unrelated events among them; and a long history is rebuilt in
 * a time that grows with its length, not with its length times its actions.
 *
 * Technical: F-31d, {@link ActionLedgerView#replay} against {@link Ledger}'s own fold of {@link ActionLedgerView#apply}.
 */
class ActionLedgerViewTest {
    private static final String[] STATES = {"PREPARED", "DISPATCHED", "VERIFIED", "FAILED", "CANCELLED", "UNCERTAIN"};

    private static WorldEvent event(String kind, String subject, Map<String, String> data, long id) {
        return WorldEvent.recorded(id, id, "", kind, null, subject, "", data).withId(id);
    }

    /** What {@link Ledger#replay} gives by default: each event applied in turn. */
    private static Map<UUID, ActionLedgerView.Row> applied(List<WorldEvent> events) {
        var view = ActionLedgerView.INSTANCE;
        Map<UUID, ActionLedgerView.Row> state = view.empty();
        for (WorldEvent event : events) if (view.kinds().contains(event.kind())) state = view.apply(state, event);
        return state;
    }

    private static List<WorldEvent> history(int actions, int events, long seed) {
        var random = new Random(seed);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < actions; i++) ids.add(new UUID(seed, i));
        var out = new ArrayList<WorldEvent>();
        for (int i = 1; i <= events; i++) {
            String subject = ids.get(random.nextInt(ids.size())).toString();
            String state = STATES[random.nextInt(STATES.length)];
            String revision = String.valueOf(random.nextInt(5));
            out.add(switch (random.nextInt(10)) {
                case 0 -> event(ActionLedgerView.FOLDED, subject, Map.of("state", state, "revision", revision, "folded", "3"), i);
                case 1 -> event(ActionLedgerView.JOURNAL, subject, Map.of("state", state, "revision", revision), i);
                case 2 -> event(ActionLedgerView.ACTION, "not an id", Map.of("state", state, "revision", revision), i);
                case 3 -> event(ActionLedgerView.ACTION, subject, Map.of("state", state, "revision", "not a number"), i);
                case 4 -> event(ActionLedgerView.ACTION, subject, Map.of("revision", revision), i);
                default -> event(ActionLedgerView.ACTION, subject, Map.of("state", state, "revision", revision), i);
            });
        }
        return out;
    }

    @Test
    void rebuiltInOnePassItIsWhatItWasRebuiltAnEventAtATime() {
        for (long seed = 1; seed <= 20; seed++) {
            var events = history(1 + (int) seed * 7, 400, seed);
            var once = ActionLedgerView.INSTANCE.replay(events);
            assertEquals(applied(events), once, "seed " + seed);
            assertEquals(List.copyOf(applied(events).keySet()), List.copyOf(once.keySet()), "In the same order, seed " + seed);
            assertThrows(UnsupportedOperationException.class, () -> once.put(UUID.randomUUID(), new ActionLedgerView.Row("VERIFIED", 1)));
        }
        assertEquals(Map.of(), ActionLedgerView.INSTANCE.replay(List.of()), "Nothing recorded, nothing kept");
        var events = history(50, 300, 99);
        var kept = applied(events);
        assertTrue(ActionLedgerView.INSTANCE.check(events, kept).agrees());
        var other = new java.util.TreeMap<>(kept);
        other.remove(other.firstKey());
        assertFalse(ActionLedgerView.INSTANCE.check(events, other).agrees(), "A row missing still differs");
    }

    @Test
    void aLongHistoryIsRebuiltInOnePass() {
        // About the size of an evaluation's last check (e40, 144,000 ticks): before, a copy of the ledger for each
        // event, some 10^9 rows copied; a pass now. Generous, so that only the old growth fails it.
        var events = history(20_000, 120_000, 7);
        long start = System.nanoTime();
        var once = ActionLedgerView.INSTANCE.replay(events);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(once.size() > 19_000);
        assertTrue(ms < 2_000, "Rebuilt in " + ms + " ms");
    }
}
