package dev.kindred.core.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: what one AI player tells its teammates through the team, never in chat: what it is doing, what it needs,
 * what it could spare, and where it is while the team shares places. It is taken from its own record, so it says only what
 * is so.
 *
 * Technical: E-50's structured update between AI players, the ledger's second source in trust order. Built by the runtime
 * from the sender's own state (its task or goal, its follow-up queue's work, its unpromised finds) and handed to each
 * teammate's ledger directly. Bounded: eight items of each kind, each at most a hundred characters on one line.
 */
public record PeerUpdate(UUID from, String name, String doing, List<String> needs, List<String> spare, PeerLedger.Place place) {
    public static final int MAX_ITEMS = 8;
    public static final int MAX_WORDS = 100;

    public PeerUpdate {
        Objects.requireNonNull(from);
        name = name == null ? "" : line(name);
        doing = doing == null ? "" : line(doing);
        needs = bounded(needs);
        spare = bounded(spare);
    }

    private static List<String> bounded(List<String> items) {
        if (items == null) return List.of();
        var out = new ArrayList<String>();
        for (String item : items) {
            if (item == null || item.isBlank()) continue;
            out.add(line(item));
            if (out.size() == MAX_ITEMS) break;
        }
        return List.copyOf(out);
    }

    private static String line(String text) {
        String flat = text.replaceAll("[\\p{Cntrl}§]", " ").replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_WORDS ? flat : flat.substring(0, MAX_WORDS);
    }
}
