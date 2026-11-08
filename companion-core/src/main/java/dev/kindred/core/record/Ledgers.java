package dev.kindred.core.record;

import dev.kindred.core.factions.Coins;
import dev.kindred.core.factions.FactionBook;
import dev.kindred.core.factions.FactionLedger;
import dev.kindred.core.factions.PowerBook;
import dev.kindred.core.factions.PowerLedger;
import dev.kindred.core.match.MatchRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * In plain terms: the exact facts the server keeps from the World Record, all together: matches, claimed land,
 * deaths, factions and power, each always rebuildable from the Record alone.
 *
 * Technical: The set of ledgers the runtime keeps live (F-31). {@link #apply} folds one written event into each ledger
 * that reads its kind; {@link #replay} rebuilds all of them from the Record; {@link #check} is the agreement check. Later
 * slices (power, coins, the market) add their ledgers here.
 */
public record Ledgers(Map<UUID, MatchRegistry.Stored> matches, Map<String, String> claims, Map<UUID, Integer> deaths, FactionBook factions,
                      PowerBook power, Coins.Book coins) {
    public static final Ledgers EMPTY = new Ledgers(MatchLedger.INSTANCE.empty(), ClaimLedger.INSTANCE.empty(), DeathLedger.INSTANCE.empty(),
            FactionLedger.INSTANCE.empty(), PowerLedger.INSTANCE.empty(), Coins.INSTANCE.empty());

    /** Every kind any of these ledgers reads: what a restart reads back from the Record. */
    public static Set<String> kinds() {
        var kinds = new TreeSet<String>();
        kinds.addAll(MatchLedger.INSTANCE.kinds());
        kinds.addAll(ClaimLedger.INSTANCE.kinds());
        kinds.addAll(DeathLedger.INSTANCE.kinds());
        kinds.addAll(FactionLedger.INSTANCE.kinds());
        kinds.addAll(PowerLedger.INSTANCE.kinds());
        kinds.addAll(Coins.INSTANCE.kinds());
        return kinds;
    }

    public Ledgers apply(WorldEvent event) {
        var m = MatchLedger.INSTANCE.kinds().contains(event.kind()) ? MatchLedger.INSTANCE.apply(matches, event) : matches;
        var c = ClaimLedger.INSTANCE.kinds().contains(event.kind()) ? ClaimLedger.INSTANCE.apply(claims, event) : claims;
        var d = DeathLedger.INSTANCE.kinds().contains(event.kind()) ? DeathLedger.INSTANCE.apply(deaths, event) : deaths;
        var f = FactionLedger.INSTANCE.kinds().contains(event.kind()) ? FactionLedger.INSTANCE.apply(factions, event) : factions;
        var p = PowerLedger.INSTANCE.kinds().contains(event.kind()) ? PowerLedger.INSTANCE.apply(power, event) : power;
        var o = Coins.INSTANCE.kinds().contains(event.kind()) ? Coins.INSTANCE.apply(coins, event) : coins;
        return m == matches && c == claims && d == deaths && f == factions && p == power && o == coins ? this : new Ledgers(m, c, d, f, p, o);
    }

    public static Ledgers replay(Iterable<WorldEvent> events) {
        Ledgers state = EMPTY;
        for (WorldEvent event : events) state = state.apply(event);
        return state;
    }

    /** The agreement check of each ledger against the Record. */
    public List<Ledger.Agreement<?>> check(List<WorldEvent> events) {
        return List.of(MatchLedger.INSTANCE.check(events, matches), ClaimLedger.INSTANCE.check(events, claims),
                DeathLedger.INSTANCE.check(events, deaths), FactionLedger.INSTANCE.check(events, factions), PowerLedger.INSTANCE.check(events, power),
                Coins.INSTANCE.check(events, coins));
    }

    /** Whether every ledger agrees with the Record. */
    public boolean agrees(List<WorldEvent> events) { return check(events).stream().allMatch(Ledger.Agreement::agrees); }
}
