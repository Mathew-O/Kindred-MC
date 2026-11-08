package dev.kindred.core.record;

import dev.kindred.core.factions.Capture;
import dev.kindred.core.factions.Coins;
import dev.kindred.core.factions.FactionBook;
import dev.kindred.core.factions.FactionLedger;
import dev.kindred.core.factions.PowerBook;
import dev.kindred.core.factions.PowerLedger;
import dev.kindred.core.factions.SafeZones;
import dev.kindred.core.market.AuctionBook;
import dev.kindred.core.market.Auctions;
import dev.kindred.core.market.MarketBook;
import dev.kindred.core.market.MarketLedger;
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
                      PowerBook power, Coins.Book coins, SafeZones.Book safe, MarketBook market,
                      AuctionBook auctions, Capture.Book captures) {
    public static final Ledgers EMPTY = new Ledgers(MatchLedger.INSTANCE.empty(), ClaimLedger.INSTANCE.empty(), DeathLedger.INSTANCE.empty(),
            FactionLedger.INSTANCE.empty(), PowerLedger.INSTANCE.empty(), Coins.INSTANCE.empty(),
            SafeZones.INSTANCE.empty(), MarketLedger.INSTANCE.empty(), Auctions.INSTANCE.empty(),
            Capture.INSTANCE.empty());

    /** Every kind any of these ledgers reads: what a restart reads back from the Record. */
    public static Set<String> kinds() {
        var kinds = new TreeSet<String>();
        kinds.addAll(MatchLedger.INSTANCE.kinds());
        kinds.addAll(ClaimLedger.INSTANCE.kinds());
        kinds.addAll(DeathLedger.INSTANCE.kinds());
        kinds.addAll(FactionLedger.INSTANCE.kinds());
        kinds.addAll(PowerLedger.INSTANCE.kinds());
        kinds.addAll(Coins.INSTANCE.kinds());
        kinds.addAll(SafeZones.INSTANCE.kinds());
        kinds.addAll(MarketLedger.INSTANCE.kinds());
        kinds.addAll(Auctions.INSTANCE.kinds());
        kinds.addAll(Capture.INSTANCE.kinds());
        return kinds;
    }

    public Ledgers apply(WorldEvent event) {
        var m = MatchLedger.INSTANCE.kinds().contains(event.kind()) ? MatchLedger.INSTANCE.apply(matches, event) : matches;
        var c = ClaimLedger.INSTANCE.kinds().contains(event.kind()) ? ClaimLedger.INSTANCE.apply(claims, event) : claims;
        var d = DeathLedger.INSTANCE.kinds().contains(event.kind()) ? DeathLedger.INSTANCE.apply(deaths, event) : deaths;
        var f = FactionLedger.INSTANCE.kinds().contains(event.kind()) ? FactionLedger.INSTANCE.apply(factions, event) : factions;
        var p = PowerLedger.INSTANCE.kinds().contains(event.kind()) ? PowerLedger.INSTANCE.apply(power, event) : power;
        var o = Coins.INSTANCE.kinds().contains(event.kind()) ? Coins.INSTANCE.apply(coins, event) : coins;
        var s = SafeZones.INSTANCE.kinds().contains(event.kind()) ? SafeZones.INSTANCE.apply(safe, event) : safe;
        var k = MarketLedger.INSTANCE.kinds().contains(event.kind()) ? MarketLedger.INSTANCE.apply(market, event) : market;
        var a = Auctions.INSTANCE.kinds().contains(event.kind()) ? Auctions.INSTANCE.apply(auctions, event) : auctions;
        var x = Capture.INSTANCE.kinds().contains(event.kind()) ? Capture.INSTANCE.apply(captures, event) : captures;
        return m == matches && c == claims && d == deaths && f == factions && p == power && o == coins && s == safe && k == market && a == auctions
                && x == captures ? this : new Ledgers(m, c, d, f, p, o, s, k, a, x);
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
                Coins.INSTANCE.check(events, coins), SafeZones.INSTANCE.check(events, safe),
                MarketLedger.INSTANCE.check(events, market), Auctions.INSTANCE.check(events, auctions),
                Capture.INSTANCE.check(events, captures));
    }

    /** Whether every ledger agrees with the Record. */
    public boolean agrees(List<WorldEvent> events) { return check(events).stream().allMatch(Ledger.Agreement::agrees); }
}
