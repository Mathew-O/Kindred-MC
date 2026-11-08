package dev.kindred.core.record;

import dev.kindred.core.match.MatchRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * In plain terms: the exact facts the server keeps from the World Record, all together: matches, claimed land and
 * deaths, each always rebuildable from the Record alone.
 *
 * Technical: The set of ledgers the runtime keeps live (F-31). {@link #apply} folds one written event into each ledger
 * that reads its kind; {@link #replay} rebuilds all of them from the Record; {@link #check} is the agreement check. Later
 * slices (power, coins, the market) add their ledgers here.
 */
public record Ledgers(Map<UUID, MatchRegistry.Stored> matches, Map<String, String> claims, Map<UUID, Integer> deaths) {
    public static final Ledgers EMPTY = new Ledgers(MatchLedger.INSTANCE.empty(), ClaimLedger.INSTANCE.empty(), DeathLedger.INSTANCE.empty());

    /** Every kind any of these ledgers reads: what a restart reads back from the Record. */
    public static Set<String> kinds() {
        var kinds = new TreeSet<String>();
        kinds.addAll(MatchLedger.INSTANCE.kinds());
        kinds.addAll(ClaimLedger.INSTANCE.kinds());
        kinds.addAll(DeathLedger.INSTANCE.kinds());
        return kinds;
    }

    public Ledgers apply(WorldEvent event) {
        var m = MatchLedger.INSTANCE.kinds().contains(event.kind()) ? MatchLedger.INSTANCE.apply(matches, event) : matches;
        var c = ClaimLedger.INSTANCE.kinds().contains(event.kind()) ? ClaimLedger.INSTANCE.apply(claims, event) : claims;
        var d = DeathLedger.INSTANCE.kinds().contains(event.kind()) ? DeathLedger.INSTANCE.apply(deaths, event) : deaths;
        return m == matches && c == claims && d == deaths ? this : new Ledgers(m, c, d);
    }

    public static Ledgers replay(Iterable<WorldEvent> events) {
        Ledgers state = EMPTY;
        for (WorldEvent event : events) state = state.apply(event);
        return state;
    }

    /** The agreement check of each ledger against the Record. */
    public List<Ledger.Agreement<?>> check(List<WorldEvent> events) {
        return List.of(MatchLedger.INSTANCE.check(events, matches), ClaimLedger.INSTANCE.check(events, claims),
                DeathLedger.INSTANCE.check(events, deaths));
    }

    /** Whether every ledger agrees with the Record. */
    public boolean agrees(List<WorldEvent> events) { return check(events).stream().allMatch(Ledger.Agreement::agrees); }
}
