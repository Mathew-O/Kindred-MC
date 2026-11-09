package dev.kindred.core.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In plain terms: the auction house as it stands: the listings open, each with its item held for it, its starting price,
 * its buy-now price, the best bid and when it closes; the buy orders open with the coins put up for them; the items
 * owed to players (won, or returned unsold); and the trades of the last week, for the wash-trading filter.
 *
 * Technical: The auction ledger's state (M-06h). Prices are in hundredths of a coin. An item in escrow is kept as its
 * serialised stack ({@code item}) with its market key; the adapter alone turns it back into a stack, once, on delivery.
 */
public record AuctionBook(Map<String, Listing> listings, Map<String, Order> orders, Map<String, Delivery> owed, List<Trade> trades) {
    public static final AuctionBook EMPTY = new AuctionBook(Map.of(), Map.of(), Map.of(), List.of());

    /** A listing: its seller, item, prices, best bid so far, and close. */
    public record Listing(String id, UUID seller, String key, String item, long start, long buyNow, long closesAt, UUID bidder, long bid) {
        public Listing {
            Objects.requireNonNull(id);
            Objects.requireNonNull(seller);
            Objects.requireNonNull(key);
            Objects.requireNonNull(item);
        }

        Listing bid(UUID by, long amount) { return new Listing(id, seller, key, item, start, buyNow, closesAt, by, amount); }
    }

    /** A buy order: its buyer, the item's key, how many still wanted at what each, the coins it still holds. */
    public record Order(String id, UUID buyer, String key, int count, long each) {}

    /** An item owed to a player: won at auction, filled for an order, or returned unsold. */
    public record Delivery(String id, UUID to, String item, String why) {}

    /** A settled trade between two players, for the filter. */
    public record Trade(UUID seller, UUID buyer, String key, long cents, long atMs) {}

    public AuctionBook {
        listings = Collections.unmodifiableMap(new TreeMap<>(listings));
        orders = Collections.unmodifiableMap(new TreeMap<>(orders));
        owed = Collections.unmodifiableMap(new TreeMap<>(owed));
        trades = List.copyOf(trades);
    }

    public List<Listing> of(UUID seller) { return listings.values().stream().filter(l -> l.seller().equals(seller)).toList(); }

    public List<Delivery> owedTo(UUID player) { return owed.values().stream().filter(d -> d.to().equals(player)).toList(); }

    AuctionBook put(Listing listing) {
        var next = new TreeMap<>(listings);
        next.put(listing.id(), listing);
        return new AuctionBook(next, orders, owed, trades);
    }

    AuctionBook remove(String listing) {
        var next = new TreeMap<>(listings);
        next.remove(listing);
        return new AuctionBook(next, orders, owed, trades);
    }

    AuctionBook put(Order order) {
        var next = new TreeMap<>(orders);
        if (order.count() <= 0) next.remove(order.id()); else next.put(order.id(), order);
        return new AuctionBook(listings, next, owed, trades);
    }

    AuctionBook owe(Delivery delivery) {
        var next = new TreeMap<>(owed);
        next.put(delivery.id(), delivery);
        return new AuctionBook(listings, orders, next, trades);
    }

    AuctionBook delivered(String delivery) {
        var next = new TreeMap<>(owed);
        next.remove(delivery);
        return new AuctionBook(listings, orders, next, trades);
    }

    AuctionBook traded(Trade trade) {
        var next = new ArrayList<>(trades);
        next.add(trade);
        long cut = trade.atMs() - Auctions.FILTER_MS;
        next.removeIf(t -> t.atMs() < cut);
        return new AuctionBook(listings, orders, owed, next);
    }
}
