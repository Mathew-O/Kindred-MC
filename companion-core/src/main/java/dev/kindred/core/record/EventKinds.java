package dev.kindred.core.record;

import dev.kindred.core.chronicle.Chronicle;
import dev.kindred.core.factions.Capture;
import dev.kindred.core.factions.Coins;
import dev.kindred.core.factions.FactionLedger;
import dev.kindred.core.factions.PowerLedger;
import dev.kindred.core.factions.SafeZones;
import dev.kindred.core.knowledge.RecordFeed;
import dev.kindred.core.knowledge.WebFeed;
import dev.kindred.core.market.Auctions;
import dev.kindred.core.market.MarketLedger;
import dev.kindred.core.wire.TeamState;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In plain terms: the list of the kinds of thing the World Record keeps (a death, a claim, a sale), and who added each.
 * Kindred's own kinds are on it from the start; an integration adds its own (a capture, a catch, a battle) without
 * touching core, and the Record refuses a kind no one added.
 *
 * Technical: event kinds are strings, not an enum (the owner's mod-support decision 4, F-31). Each kind is registered once
 * by one owner; registering it again by the same owner is a no-op, by another an error. The saved values are the strings
 * themselves, unchanged: a kind's constant stays in the class that folds or writes it (and stays a compile-time constant,
 * so switches over kinds keep working), and is registered here by value. A saved event whose kind is no longer
 * registered (an integration removed) is still read; only new writes are checked ({@code SqliteCompanionStore.record}).
 */
public final class EventKinds {
    /** One kind and the owner that registered it: {@code core} for Kindred's own, an integration's id otherwise. */
    public record Kind(String id, String owner) {}

    /** The owner of Kindred's own kinds. */
    public static final String CORE = "core";

    private static final ConcurrentHashMap<String, Kind> KINDS = new ConcurrentHashMap<>();

    static {
        for (String kind : List.of(
                // The Record's own ledgers (F-31a) and the journal and action ledger written as events.
                ActionLedgerView.ACTION, ActionLedgerView.FOLDED, ActionLedgerView.JOURNAL, ClaimLedger.CLAIM, ClaimLedger.UNCLAIM, ClaimLedger.CAPTURE,
                DeathLedger.DEATH, MatchLedger.MATCH, Ledgers.CHECK,
                // Factions (M-06).
                FactionLedger.CREATE, FactionLedger.INVITE, FactionLedger.JOIN, FactionLedger.LEAVE, FactionLedger.RANK,
                FactionLedger.WISH, FactionLedger.HOME, FactionLedger.COLOUR, FactionLedger.DISBAND,
                PowerLedger.ONLINE, PowerLedger.LOSS, PowerLedger.SET, PowerLedger.SEEN,
                Capture.VULNERABLE, Capture.SECURE, Coins.KIND, SafeZones.KIND,
                // The market and the auction house (M-06g, M-06h).
                MarketLedger.SALE, MarketLedger.BUY, MarketLedger.BASELINE, MarketLedger.SIGNAL,
                Auctions.LIST, Auctions.BID, Auctions.CLOSE, Auctions.CANCEL, Auctions.ORDER, Auctions.FILL,
                Auctions.ORDER_CANCEL, Auctions.DELIVERING, Auctions.DELIVERED,
                // The Wire, the knowledge web and the chronicle (F-32, F-33, F-34).
                WebFeed.MESSAGE, RecordFeed.CORRECTION, TeamState.KIND, Chronicle.PAGE)) {
            register(kind, CORE);
        }
    }

    private EventKinds() {}

    /**
     * Registers {@code id} for {@code owner} and returns it. Again by the same owner it is a no-op; a kind another owner
     * holds is refused, so two integrations never write one kind meaning two things.
     */
    public static String register(String id, String owner) {
        if (!WorldEvent.validKind(id)) throw new IllegalArgumentException("Invalid event kind " + id);
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("An event kind needs an owner");
        Kind held = KINDS.putIfAbsent(id, new Kind(id, owner));
        if (held != null && !held.owner().equals(owner)) {
            throw new IllegalStateException("Event kind " + id + " is " + held.owner() + "'s, not " + owner + "'s");
        }
        return id;
    }

    /** Whether {@code id} is a registered kind: the Record writes only these. */
    public static boolean known(String id) { return id != null && KINDS.containsKey(id); }

    /** The kind registered as {@code id}, if any. */
    public static Optional<Kind> of(String id) { return id == null ? Optional.empty() : Optional.ofNullable(KINDS.get(id)); }

    /** Every registered kind, by id. */
    public static List<Kind> all() { return KINDS.values().stream().sorted(Comparator.comparing(Kind::id)).toList(); }
}
