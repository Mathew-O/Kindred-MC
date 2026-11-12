package dev.kindred.core.eval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In plain terms: an evaluation run's check that every item a player came to hold came from somewhere: a block it
 * broke, a drop it picked up, a craft, a chest, the market. The run's inventories are counted every second; each rise
 * must be paid for by a source the game reported in the same second or the one either side of it. A rise nothing pays
 * for is an item from nowhere. Items going (placed, eaten, tossed, used up) are never questioned.
 *
 * Technical: The item-source audit's matcher (M-06k), pure and single-threaded. A window is the time between two
 * snapshots. {@link #credit} records a source's gain of {@code count} of an item key; {@link #observe} takes one
 * player's counts by key at a snapshot, and each rise over the last snapshot is paid from that player's credits for
 * the key, oldest first; what is left unpaid is a debt. A later credit pays debts first. Credits and debts are carried
 * {@link #CARRY} window: a source reported in one window pays a rise seen in the next, and the other way round. At
 * {@link #endWindow} a debt older than that is a violation and a credit older than that lapses unused. A player's
 * first snapshot is its baseline; in a run's audit holding anything then is a violation too ({@code started with}),
 * since a run starts from empty inventories.
 */
public final class ItemSourceLedger {
    /** How many windows a credit or a debt is carried before it lapses or is a violation. */
    public static final int CARRY = 1;
    /** At most this many violations are listed; the rest are counted. */
    public static final int LISTED = 100;

    /** What the audit found. */
    public record Report(long windows, long gained, long paid, Map<String, Long> bySource, long lapsed, int violationCount, List<String> violations) {
        public Report {
            bySource = java.util.Collections.unmodifiableMap(new TreeMap<>(bySource));
            violations = List.copyOf(violations);
        }

        public boolean clean() { return violationCount == 0; }
    }

    private static final class Lot {
        long amount;
        final long window;
        final String source;

        Lot(long amount, long window, String source) {
            this.amount = amount;
            this.window = window;
            this.source = source;
        }
    }

    private final Map<UUID, Map<String, Integer>> last = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Map<String, ArrayDeque<Lot>>> credits = new HashMap<>();
    private final Map<UUID, Map<String, ArrayDeque<Lot>>> debts = new HashMap<>();
    private final Map<String, Long> bySource = new LinkedHashMap<>();
    private final List<String> violations = new ArrayList<>();
    private int violationCount;
    private long window, gained, paid, lapsed;
    private final boolean fromEmpty;

    /** A run's audit: every player starts from an empty inventory, so one first seen holding anything is a violation. */
    public ItemSourceLedger() { this(true); }

    /** {@code fromEmpty} false: a player's first snapshot is its baseline whatever it holds (a fixture's staged items). */
    public ItemSourceLedger(boolean fromEmpty) { this.fromEmpty = fromEmpty; }

    /** A source's gain of {@code count} of {@code key} for {@code player}: pays the player's debts for it first. */
    public void credit(UUID player, String key, int count, String source) {
        if (count <= 0) return;
        bySource.merge(source, (long) count, Long::sum);
        long left = count;
        var owed = debts.getOrDefault(player, Map.of()).get(key);
        while (owed != null && left > 0 && !owed.isEmpty()) {
            var debt = owed.peekFirst();
            long take = Math.min(left, debt.amount);
            debt.amount -= take;
            left -= take;
            paid += take;
            if (debt.amount == 0) owed.pollFirst();
        }
        if (left > 0) credits.computeIfAbsent(player, p -> new HashMap<>()).computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(new Lot(left, window, source));
    }

    /** One player's counts by key at this window's snapshot. */
    public void observe(UUID player, String name, Map<String, Integer> now, long tick) {
        names.put(player, name);
        var before = last.get(player);
        if (before == null) {
            last.put(player, Map.copyOf(now));
            var held = new TreeMap<String, Integer>();
            for (var e : now.entrySet()) if (e.getValue() > 0) held.put(e.getKey(), e.getValue());
            if (fromEmpty && !held.isEmpty()) violation("tick " + tick + ": " + name + " started with " + held);
            return;
        }
        var keys = new HashSet<>(now.keySet());
        keys.addAll(before.keySet());
        for (String key : keys) {
            long rise = (long) now.getOrDefault(key, 0) - before.getOrDefault(key, 0);
            if (rise <= 0) continue;
            gained += rise;
            var lots = credits.getOrDefault(player, Map.of()).get(key);
            while (lots != null && rise > 0 && !lots.isEmpty()) {
                var lot = lots.peekFirst();
                long take = Math.min(rise, lot.amount);
                lot.amount -= take;
                rise -= take;
                paid += take;
                if (lot.amount == 0) lots.pollFirst();
            }
            if (rise > 0) debts.computeIfAbsent(player, p -> new HashMap<>()).computeIfAbsent(key, k -> new ArrayDeque<>())
                    .addLast(new Lot(rise, window, "tick " + tick));
        }
        last.put(player, Map.copyOf(now));
    }

    /** The window ends: debts carried too long are violations, credits carried too long lapse. */
    public void endWindow() {
        for (var player : debts.entrySet()) {
            for (var key : player.getValue().entrySet()) {
                var owed = key.getValue();
                while (!owed.isEmpty() && window - owed.peekFirst().window >= CARRY) {
                    var debt = owed.pollFirst();
                    violation(debt.source + ": " + names.getOrDefault(player.getKey(), player.getKey().toString()) + " gained " + debt.amount + " "
                            + key.getKey() + " with no source");
                }
            }
        }
        for (var player : credits.values()) {
            for (var lots : player.values()) {
                while (!lots.isEmpty() && window - lots.peekFirst().window >= CARRY) lapsed += lots.pollFirst().amount;
            }
        }
        window++;
    }

    public Report report() {
        return new Report(window, gained, paid, bySource, lapsed, violationCount, violations);
    }

    private void violation(String text) {
        violationCount++;
        if (violations.size() < LISTED) violations.add(text);
    }
}
