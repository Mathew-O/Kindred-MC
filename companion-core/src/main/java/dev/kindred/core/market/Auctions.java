package dev.kindred.core.market;

import dev.kindred.core.factions.Coins;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Factions;
import dev.kindred.core.record.Ledger;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: the auction house's rules. A player lists an item (25 coins, at most five at a time, up to 48 hours);
 * others bid, their coins held until they are outbid or win; at its close the best bid wins, the seller is paid less 3%,
 * and the fee is gone from the world; unsold, the item goes back. A buy order holds its coins until a later listing of
 * the same item fills it. Nothing between players ever makes coins: the buyer's coins move, less the fee. A trade the
 * filter does not count (the same two accounts trading the same item back and forth, or one account with itself)
 * tells the market nothing; one it counts nudges the market's price up a little.
 *
 * Technical: Pure decisions and the ledger (M-06h). Every decision returns the events to write, with the coins events
 * that go with them, or the reason it is refused; the adapter writes them as one transaction. Closes are found by
 * {@link #due} from the ledger itself, so a restart loses none.
 */
public final class Auctions implements Ledger<AuctionBook> {
    public static final String LIST = "auction.list", BID = "auction.bid", CLOSE = "auction.close", CANCEL = "auction.cancel",
            ORDER = "auction.order", FILL = "auction.order.fill", ORDER_CANCEL = "auction.order.cancel", DELIVERING = "auction.delivering",
            DELIVERED = "auction.delivered";
    /** Trades kept for the filter: a week. */
    static final long FILTER_MS = 7L * 24 * 3_600_000;
    /** A counted trade nudges the market's stock of what it is made of by this share of the units traded. */
    public static final double SIGNAL_WEIGHT = 0.1;
    public static final Auctions INSTANCE = new Auctions();

    /** A decision: the events to write, or why it is refused. */
    public record Decision(List<WorldEvent> events, String reason) {
        public Decision { events = List.copyOf(events); }
        public boolean accepted() { return !events.isEmpty(); }
        static Decision refused(String why) { return new Decision(List.of(), why); }
    }

    // ----- The ledger -----

    @Override public String name() { return "auctions"; }

    @Override public Set<String> kinds() { return Set.of(LIST, BID, CLOSE, CANCEL, ORDER, FILL, ORDER_CANCEL, DELIVERED); }

    @Override public AuctionBook empty() { return AuctionBook.EMPTY; }

    @Override
    public AuctionBook apply(AuctionBook book, WorldEvent e) {
        try {
            return switch (e.kind()) {
                case LIST -> book.put(new AuctionBook.Listing(e.subject(), e.actor(), e.get("key", ""), e.get("item", ""), Long.parseLong(e.get("start", "")),
                        Long.parseLong(e.get("buyNow", "0")), Long.parseLong(e.get("closesAt", "")), null, 0));
                case BID -> {
                    var listing = book.listings().get(e.subject());
                    yield listing == null ? book : book.put(listing.bid(e.actor(), Long.parseLong(e.get("amount", ""))));
                }
                case CLOSE, CANCEL -> {
                    var listing = book.listings().get(e.subject());
                    if (listing == null) yield book;
                    var next = book.remove(e.subject()).owe(new AuctionBook.Delivery(e.get("delivery", ""), UUID.fromString(e.get("to", "")), listing.item(), e.get("outcome", e.kind())));
                    if (!e.get("buyer", "").isEmpty()) {
                        next = next.traded(new AuctionBook.Trade(listing.seller(), UUID.fromString(e.get("buyer", "")), listing.key(), Long.parseLong(e.get("amount", "0")), e.realTimeMs()));
                    }
                    yield next;
                }
                case ORDER -> book.put(new AuctionBook.Order(e.subject(), e.actor(), e.get("key", ""), Integer.parseInt(e.get("count", "")), Long.parseLong(e.get("each", ""))));
                case FILL -> {
                    var order = book.orders().get(e.subject());
                    yield order == null ? book : book.put(new AuctionBook.Order(order.id(), order.buyer(), order.key(), Integer.parseInt(e.get("left", "")), order.each()));
                }
                case ORDER_CANCEL -> {
                    var order = book.orders().get(e.subject());
                    yield order == null ? book : book.put(new AuctionBook.Order(order.id(), order.buyer(), order.key(), 0, order.each()));
                }
                case DELIVERED -> book.delivered(e.subject());
                default -> book;
            };
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return book;
        }
    }

    // ----- Decisions -----

    /**
     * {@code seller} lists {@code count} of the item keyed {@code key} (serialised as {@code item}) for {@code hours} at
     * {@code start}, with {@code buyNow} (zero for none). A buy order for the same key that would pay at least the start
     * for all of it fills it at once.
     */
    public static Decision list(AuctionBook book, Coins.Book coins, FactionSettings settings, UUID seller, String id, String key, String item, int count,
                                long start, long buyNow, int hours, Factions.At at) {
        if (book.of(seller).size() >= settings.auctionListings()) return Decision.refused("At most " + settings.auctionListings() + " listings at a time");
        if (hours < 1 || hours > settings.auctionHours()) return Decision.refused("A listing lasts 1 to " + settings.auctionHours() + " hours");
        if (start < Coins.CENT) return Decision.refused("A starting price is at least one coin");
        if (buyNow != 0 && buyNow < start) return Decision.refused("A buy-now price is at least the starting price");
        long fee = settings.auctionListingFee() * Coins.CENT;
        var events = new ArrayList<WorldEvent>();
        var paid = Coins.change(coins, seller, -fee, "auction listing fee " + id, at, "");
        if (paid.isEmpty()) return Decision.refused("Listing costs " + settings.auctionListingFee() + " coins; you have " + Coins.show(coins.of(seller)));
        var data = new LinkedHashMap<String, String>();
        data.put("key", key);
        data.put("item", item);
        data.put("count", String.valueOf(count));
        data.put("start", String.valueOf(start));
        data.put("buyNow", String.valueOf(buyNow));
        data.put("closesAt", String.valueOf(at.realTimeMs() + hours * 3_600_000L));
        events.add(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), LIST, seller, id, "", data));
        events.add(paid.get());
        var balances = Coins.INSTANCE.apply(coins, paid.get());
        // A standing order that pays at least the start for the whole stack takes it now, the best price first.
        var order = book.orders().values().stream().filter(o -> o.key().equals(key) && o.count() >= count && !o.buyer().equals(seller) && o.each() * count >= start)
                .max(java.util.Comparator.comparingLong(AuctionBook.Order::each)).orElse(null);
        if (order != null) {
            var listing = new AuctionBook.Listing(id, seller, key, item, start, buyNow, at.realTimeMs() + hours * 3_600_000L, order.buyer(), order.each() * count);
            events.addAll(settle(listing, balances, settings, "order", at));
            events.add(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), FILL, order.buyer(), order.id(), "", Map.of("left", String.valueOf(order.count() - count), "listing", id)));
        }
        return new Decision(events, order != null ? "Sold at once to a buy order for " + Coins.show(order.each() * count) : "Listed");
    }

    /** {@code bidder} bids {@code amount} on {@code id}: held until outbid or the close; at or above buy-now it wins at once. */
    public static Decision bid(AuctionBook book, Coins.Book coins, FactionSettings settings, UUID bidder, String id, long amount, Factions.At at) {
        var listing = book.listings().get(id);
        if (listing == null) return Decision.refused("No such listing");
        if (listing.seller().equals(bidder)) return Decision.refused("Not on your own listing");
        if (at.realTimeMs() >= listing.closesAt()) return Decision.refused("It has closed");
        long least = listing.bidder() == null ? listing.start() : listing.bid() + Math.max(Coins.CENT, listing.bid() / 20);
        if (listing.buyNow() > 0) least = Math.min(least, listing.buyNow());
        if (amount < least) return Decision.refused("A bid is at least " + Coins.show(least));
        if (listing.buyNow() > 0) amount = Math.min(amount, listing.buyNow());
        var events = new ArrayList<WorldEvent>();
        var held = Coins.change(coins, bidder, -amount, "bid " + id, at, "");
        if (held.isEmpty()) return Decision.refused("You have " + Coins.show(coins.of(bidder)));
        events.add(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), BID, bidder, id, "", Map.of("amount", String.valueOf(amount))));
        events.add(held.get());
        var balances = Coins.INSTANCE.apply(coins, held.get());
        if (listing.bidder() != null) {
            var back = Coins.change(balances, listing.bidder(), listing.bid(), "outbid on " + id, at, "").orElseThrow();
            events.add(back);
            balances = Coins.INSTANCE.apply(balances, back);
        }
        if (listing.buyNow() > 0 && amount >= listing.buyNow()) {
            events.addAll(settle(listing.bid(bidder, amount), balances, settings, "bought now", at));
            return new Decision(events, "Bought now for " + Coins.show(amount));
        }
        return new Decision(events, "Bid " + Coins.show(amount));
    }

    /** The listings due to close at {@code nowMs}. */
    public static List<AuctionBook.Listing> due(AuctionBook book, long nowMs) {
        return book.listings().values().stream().filter(l -> l.closesAt() <= nowMs).toList();
    }

    /** {@code listing}'s close: to its best bidder, or back to its seller unsold. */
    public static Decision close(AuctionBook.Listing listing, Coins.Book coins, FactionSettings settings, Factions.At at) {
        return new Decision(settle(listing, coins, settings, listing.bidder() == null ? "unsold" : "won", at), listing.bidder() == null ? "Unsold" : "Won");
    }

    /** {@code seller} withdraws a listing nobody has bid on; the fee is not returned. */
    public static Decision cancel(AuctionBook book, UUID seller, String id, Factions.At at) {
        var listing = book.listings().get(id);
        if (listing == null || !listing.seller().equals(seller)) return Decision.refused("Not your listing");
        if (listing.bidder() != null) return Decision.refused("It has a bid");
        return new Decision(List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), CANCEL, seller, id, "",
                Map.of("to", seller.toString(), "delivery", "d-" + id, "outcome", "withdrawn"))), "Withdrawn; the item comes back");
    }

    /** {@code buyer} posts an order for {@code count} of {@code key} at {@code each}: its coins held until filled or withdrawn. */
    public static Decision order(Coins.Book coins, UUID buyer, String id, String key, int count, long each, Factions.At at) {
        if (count < 1 || count > 2304) return Decision.refused("An order is for 1 to 2,304");
        if (each < 1) return Decision.refused("An order pays something");
        var held = Coins.change(coins, buyer, -each * count, "buy order " + id, at, "");
        if (held.isEmpty()) return Decision.refused("It holds " + Coins.show(each * count) + "; you have " + Coins.show(coins.of(buyer)));
        var data = Map.of("key", key, "count", String.valueOf(count), "each", String.valueOf(each));
        return new Decision(List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), ORDER, buyer, id, "", data), held.get()), "Order posted");
    }

    /** {@code buyer} withdraws an order; what it still holds comes back. */
    public static Decision cancelOrder(AuctionBook book, Coins.Book coins, UUID buyer, String id, Factions.At at) {
        var order = book.orders().get(id);
        if (order == null || !order.buyer().equals(buyer)) return Decision.refused("Not your order");
        var back = Coins.change(coins, buyer, order.each() * order.count(), "order withdrawn " + id, at, "").orElseThrow();
        return new Decision(List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), ORDER_CANCEL, buyer, id, "", Map.of()), back), "Withdrawn");
    }

    /** Whether the filter counts a trade: not one account with itself, not the same item back and forth between two. */
    public static boolean counted(AuctionBook book, UUID seller, UUID buyer, String key, long nowMs) {
        if (seller.equals(buyer)) return false;
        for (var t : book.trades()) {
            if (nowMs - t.atMs() > FILTER_MS || !t.key().equals(key)) continue;
            if (t.seller().equals(buyer) && t.buyer().equals(seller)) return false;
        }
        return true;
    }

    /** The demand a counted trade signals to the market: the stock of what it is made of drawn down a little. */
    public static java.util.Optional<WorldEvent> signal(Pricing pricing, MarketBook market, AuctionBook book, AuctionBook.Listing listing, UUID buyer, int count,
                                                        Factions.At at) {
        if (!counted(book, listing.seller(), buyer, listing.key(), at.realTimeMs())) return java.util.Optional.empty();
        String item = listing.key().replaceAll("[@{].*$", "");
        var path = pricing.price(market, item, at.realTimeMs());
        if (path.isEmpty()) return java.util.Optional.empty();
        var moved = pricing.move(market, path.get().roots(), -SIGNAL_WEIGHT * count, at.realTimeMs());
        return java.util.Optional.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), MarketLedger.SIGNAL, buyer, listing.key(), "",
                Map.of("listing", listing.id(), "roots", MarketLedger.encode(moved))));
    }

    /** A listing settled: paid out less the fee (destroyed), the item owed to its winner, or back to its seller. */
    private static List<WorldEvent> settle(AuctionBook.Listing listing, Coins.Book coins, FactionSettings settings, String outcome, Factions.At at) {
        var events = new ArrayList<WorldEvent>();
        var data = new LinkedHashMap<String, String>();
        data.put("outcome", outcome);
        data.put("delivery", "d-" + listing.id());
        if (listing.bidder() == null) {
            data.put("to", listing.seller().toString());
            events.add(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), CLOSE, listing.seller(), listing.id(), "", data));
            return events;
        }
        long fee = (long) Math.ceil(listing.bid() * settings.auctionSalePercent() / 100.0);
        data.put("to", listing.bidder().toString());
        data.put("buyer", listing.bidder().toString());
        data.put("amount", String.valueOf(listing.bid()));
        data.put("fee", String.valueOf(fee));
        events.add(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), CLOSE, listing.seller(), listing.id(), "", data));
        events.add(Coins.change(coins, listing.seller(), listing.bid() - fee, "sold at auction " + listing.id(), at, "").orElseThrow());
        return events;
    }

    /** The count a listing's serialised stack holds, from its listing event. */
    public static int count(WorldEvent listing) { return Integer.parseInt(listing.get("count", "1")); }
}
