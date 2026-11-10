package dev.kindred.core.factions;

import dev.kindred.core.market.Auctions;
import dev.kindred.core.market.MarketLedger;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.DeathLedger;
import dev.kindred.core.record.Ledgers;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: a check of a faction world's whole history, read back from the World Record: every claim and capture
 * was one the rules allowed when it was made, nobody was killed by a player in a safe zone, no coin came from nowhere,
 * no balance went below nothing, no sale was paid without its intent or after it was cancelled, and nothing from the
 * auction house was handed over twice.
 *
 * Technical: The locked rules of M-06 checked from the Record alone (M-06i's evaluation scenario, and the month run's
 * pass criteria). The events are folded in order through {@link Ledgers#apply}; before each claim and capture, its
 * decision is taken again from the state just before it with the same rules the commands use ({@link Territory#claim},
 * {@link Capture#capture}) under the run's {@code settings} and the event's own time. A claim or capture those rules
 * refuse now, or one no player made, is a violation. A player's kill inside a safe zone's chunk is one too. Each
 * {@code coins} event's balance must follow from the one before by its amount and never go below zero, and coins may
 * come into being only as starting coins or a market sale. Within the auction house, coins only move between players or
 * are destroyed as fees. Every {@code market.sale} needs an earlier intent of the same player, not cancelled, and each
 * delivery is begun once and handed over at most once.
 */
public final class FactionsAudit {
    private FactionsAudit() { }

    /** What the record holds, and every rule it shows broken (empty when none was). */
    public record Report(Map<String, Integer> counts, List<String> violations) {
        public Report {
            counts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(counts));
            violations = List.copyOf(violations);
        }
        public boolean clean() { return violations.isEmpty(); }
    }

    /** Coin reasons that move coins inside the auction house: between players, or out as fees. */
    private static final List<String> AUCTION = List.of("bid ", "outbid on ", "sold at auction ", "buy order ", "order withdrawn ", "auction listing fee ");

    public static Report check(List<WorldEvent> events, FactionSettings settings) {
        var counts = new LinkedHashMap<String, Integer>();
        for (String key : List.of("events", "factions", "claims", "captures", "sales", "purchases", "listings", "deliveries", "deaths", "kills")) counts.put(key, 0);
        var violations = new ArrayList<String>();
        var state = Ledgers.EMPTY;
        var balances = new HashMap<UUID, Long>();
        var intents = new HashMap<String, UUID>();
        var cancelled = new HashSet<String>();
        var begun = new HashSet<String>();
        var delivered = new HashSet<String>();
        long auctionIn = 0, auctionOut = 0;
        // A claim or capture comes with its coins event right after it, in the same batch: checked against the state before both.
        for (var e : events) {
            counts.merge("events", 1, Integer::sum);
            switch (e.kind()) {
                case FactionLedger.CREATE -> counts.merge("factions", 1, Integer::sum);
                case ClaimLedger.CLAIM -> {
                    counts.merge("claims", 1, Integer::sum);
                    if (e.actor() == null) violations.add(at(e) + "a claim of " + e.place() + " no player made");
                    else {
                        var decision = Territory.claim(state.factions(), state.power(), state.claims(), state.safe().chunks(),
                                state.coins().of(e.actor()), settings, e.actor(), Territory.Chunk.of(e.place()), at(e, e.world()));
                        if (!decision.accepted()) violations.add(at(e) + "claim of " + e.place() + " outside the rules: " + decision.reason());
                    }
                }
                case ClaimLedger.CAPTURE -> {
                    counts.merge("captures", 1, Integer::sum);
                    if (e.actor() == null) violations.add(at(e) + "a capture of " + e.place() + " no player made");
                    else {
                        var decision = Capture.capture(state.factions(), state.power(), state.claims(), state.safe().chunks(), state.captures(),
                                state.coins().of(e.actor()), settings, e.actor(), Territory.Chunk.of(e.place()), at(e, e.world()));
                        if (!decision.accepted()) violations.add(at(e) + "capture of " + e.place() + " outside the rules: " + decision.reason());
                    }
                }
                case DeathLedger.DEATH -> {
                    counts.merge("deaths", 1, Integer::sum);
                    String killer = e.get("killer", "");
                    if (!killer.isEmpty()) {
                        counts.merge("kills", 1, Integer::sum);
                        String chunk = deathChunk(e);
                        if (chunk != null && state.safe().chunks().contains(chunk)) violations.add(at(e) + "a player killed in the safe zone at " + chunk);
                    }
                }
                case Coins.KIND -> {
                    long amount = Long.parseLong(e.get("amount", "0"));
                    long balance = Long.parseLong(e.get("balance", "0"));
                    long before = balances.getOrDefault(e.actor(), 0L);
                    if (balance != before + amount) violations.add(at(e) + "a balance of " + balance + " that does not follow from " + before + " and " + amount);
                    if (balance < 0) violations.add(at(e) + "a balance below zero: " + balance);
                    balances.put(e.actor(), balance);
                    String reason = e.get("for", "");
                    boolean auction = AUCTION.stream().anyMatch(reason::startsWith);
                    if (auction) {
                        if (amount > 0) auctionIn += amount; else auctionOut -= amount;
                    } else if (amount > 0 && !reason.equals(Coins.START) && !reason.startsWith("sold ")) {
                        violations.add(at(e) + "coins out of nowhere: " + amount + " for '" + reason + "'");
                    }
                }
                case "market.intent" -> intents.put(e.subject(), e.actor());
                case "market.cancel" -> cancelled.add(e.subject());
                case MarketLedger.SALE -> {
                    counts.merge("sales", 1, Integer::sum);
                    String intent = e.get("intent", "");
                    if (!java.util.Objects.equals(e.actor(), intents.get(intent))) violations.add(at(e) + "a sale paid with no intent of its seller's: " + intent);
                    else if (cancelled.contains(intent)) violations.add(at(e) + "a sale paid after its intent was cancelled: " + intent);
                }
                case MarketLedger.BUY -> counts.merge("purchases", 1, Integer::sum);
                case Auctions.LIST -> counts.merge("listings", 1, Integer::sum);
                case Auctions.DELIVERING -> {
                    if (!begun.add(e.subject())) violations.add(at(e) + "a delivery begun twice: " + e.subject());
                }
                case Auctions.DELIVERED -> {
                    counts.merge("deliveries", 1, Integer::sum);
                    if (!begun.contains(e.subject())) violations.add(at(e) + "a delivery handed over that was never begun: " + e.subject());
                    if (!delivered.add(e.subject())) violations.add(at(e) + "a delivery handed over twice: " + e.subject());
                }
                default -> { }
            }
            state = state.apply(e);
        }
        if (auctionIn > auctionOut) violations.add("the auction house paid out " + auctionIn + " against " + auctionOut + " taken in");
        return new Report(counts, violations);
    }

    private static String at(WorldEvent e) { return "event " + e.id() + " (tick " + e.tick() + "): "; }

    private static Factions.At at(WorldEvent e, String world) { return new Factions.At(e.tick(), e.realTimeMs(), world); }

    /** The chunk a death happened in, from its place ("x,y,z") and world; null when it does not say. */
    private static String deathChunk(WorldEvent e) {
        String[] xyz = e.place().split(",");
        if (xyz.length != 3) return null;
        try {
            return ClaimLedger.chunk(e.world(), Integer.parseInt(xyz[0].trim()) >> 4, Integer.parseInt(xyz[2].trim()) >> 4);
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    /** The kinds the audit reads from the Record. */
    public static Set<String> kinds() {
        var kinds = new HashSet<>(Ledgers.kinds());
        kinds.addAll(Set.of("market.intent", "market.cancel", MarketLedger.SALE, MarketLedger.BUY, Auctions.LIST, Auctions.DELIVERING, Auctions.DELIVERED,
                DeathLedger.DEATH, Coins.KIND));
        return kinds;
    }
}
