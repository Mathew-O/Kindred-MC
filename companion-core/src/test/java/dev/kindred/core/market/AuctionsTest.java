package dev.kindred.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.factions.Coins;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Factions;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the auction house (M-06h): the 25-coin listing fee and the 3% sale fee, both gone from the
 * world; five listings a player and 48 hours at most; bids held and handed back when outbid; a close that pays and owes
 * the item, or returns it unsold; buy now; a buy order filled by a later listing; no coin made between players; the
 * wash-trading filter; and the house rebuilt from the World Record.
 */
class AuctionsTest {
    private static final FactionSettings S = FactionSettings.DEFAULTS;
    private final UUID ana = UUID.randomUUID(), ben = UUID.randomUUID(), cy = UUID.randomUUID();
    private AuctionBook book = AuctionBook.EMPTY;
    private Coins.Book coins = new Coins.Book(Map.of(ana, 50_000L, ben, 50_000L, cy, 50_000L), 150_000);
    private final List<WorldEvent> record = new ArrayList<>();
    private long now = 1_000_000_000L;
    private int ids;

    @Test
    void aListingCostsTwentyFiveCoinsAndAWinningBidThreePercentBothDestroyed() {
        apply(list(ana, "minecraft:diamond", 10_000, 0, 24));
        assertEquals(50_000 - 2_500, coins.of(ana), "the listing fee");
        String id = book.of(ana).getFirst().id();
        apply(Auctions.bid(book, coins, S, ben, id, 12_000, at()));
        assertEquals(50_000 - 12_000, coins.of(ben), "the bid held");
        apply(Auctions.bid(book, coins, S, cy, id, 20_000, at()));
        assertEquals(50_000, coins.of(ben), "the outbid bidder paid back");
        assertEquals(50_000 - 20_000, coins.of(cy));
        now += 25 * 3_600_000L;
        var due = Auctions.due(book, now);
        assertEquals(1, due.size());
        apply(Auctions.close(due.getFirst(), coins, S, at()));
        assertEquals(50_000 - 2_500 + 20_000 - 600, coins.of(ana), "paid less 3%");
        assertTrue(book.listings().isEmpty());
        assertEquals(cy, book.owedTo(cy).getFirst().to(), "the item owed to its winner");
        long total = coins.of(ana) + coins.of(ben) + coins.of(cy);
        assertEquals(150_000 - 2_500 - 600, total, "fees leave the world; no coin is made between players");
    }

    @Test
    void capsBidsAndBuyNow() {
        for (int i = 0; i < 5; i++) apply(list(ana, "minecraft:dirt", 100, 0, 1));
        assertEquals("At most 5 listings at a time", list(ana, "minecraft:dirt", 100, 0, 1).reason());
        assertEquals("A listing lasts 1 to 48 hours", list(ben, "minecraft:dirt", 100, 0, 49).reason());
        assertEquals("A starting price is at least one coin", list(ben, "minecraft:dirt", 99, 0, 1).reason());
        String id = book.of(ana).getFirst().id();
        assertEquals("Not on your own listing", Auctions.bid(book, coins, S, ana, id, 200, at()).reason());
        assertEquals("A bid is at least 1.00", Auctions.bid(book, coins, S, ben, id, 50, at()).reason());
        apply(Auctions.bid(book, coins, S, ben, id, 100, at()));
        assertEquals("A bid is at least 2.00", Auctions.bid(book, coins, S, cy, id, 150, at()).reason(), "a coin or a twentieth more");
        apply(list(ben, "minecraft:emerald", 1_000, 5_000, 2));
        String buyNow = book.of(ben).getFirst().id();
        var decision = Auctions.bid(book, coins, S, cy, buyNow, 9_000, at());
        assertEquals("Bought now for 50.00", decision.reason());
        apply(decision);
        assertFalse(book.listings().containsKey(buyNow));
        assertEquals(50_000 - 5_000, coins.of(cy), "paid the buy-now price, not the bid");
        assertEquals("It has a bid", Auctions.cancel(book, ana, id, at()).reason());
        String other = book.of(ana).get(1).id();
        apply(Auctions.cancel(book, ana, other, at()));
        assertEquals(ana, book.owedTo(ana).getFirst().to(), "withdrawn: back to the seller");
    }

    @Test
    void anUnsoldListingComesBackAndABuyOrderIsFilledByALaterListing() {
        apply(list(ana, "minecraft:gold_ingot", 1_000, 0, 1));
        now += 2 * 3_600_000L;
        apply(Auctions.close(Auctions.due(book, now).getFirst(), coins, S, at()));
        assertEquals("unsold", book.owedTo(ana).getFirst().why());
        apply(Auctions.order(coins, ben, "o-1", "minecraft:iron_ingot", 32, 500, at()));
        assertEquals(50_000 - 16_000, coins.of(ben), "the order's coins held");
        var filled = list(cy, "minecraft:iron_ingot", 8_000, 0, 24, 16);
        assertEquals("Sold at once to a buy order for 80.00", filled.reason());
        apply(filled);
        assertEquals(16, book.orders().get("o-1").count(), "sixteen still wanted");
        assertEquals(50_000 - 2_500 + 8_000 - 240, coins.of(cy), "paid the order's price less 3%");
        assertEquals(ben, book.owedTo(ben).getFirst().to());
        apply(Auctions.cancelOrder(book, coins, ben, "o-1", at()));
        assertEquals(50_000 - 8_000, coins.of(ben), "the rest of its coins back");
        assertTrue(book.orders().isEmpty());
    }

    @Test
    void theFilterIgnoresTheSameItemBackAndForthAndOneAccountWithItself() {
        assertFalse(Auctions.counted(book, ana, ana, "minecraft:diamond", now));
        apply(list(ana, "minecraft:diamond", 10_000, 10_000, 24));
        apply(Auctions.bid(book, coins, S, ben, book.of(ana).getFirst().id(), 10_000, at()));
        assertTrue(Auctions.counted(book, cy, ben, "minecraft:diamond", now), "another pair counts");
        assertFalse(Auctions.counted(book, ben, ana, "minecraft:diamond", now), "back the other way does not");
        assertTrue(Auctions.counted(book, ben, ana, "minecraft:emerald", now), "another item does");
        assertTrue(Auctions.counted(book, ben, ana, "minecraft:diamond", now + 8 * 24 * 3_600_000L), "a week on, it does");
        var pricing = new Pricing(MarketTable.bundled(), List.of());
        var listing = new AuctionBook.Listing("x", ana, "minecraft:diamond", "", 100, 0, now, ben, 10_000);
        var signal = Auctions.signal(pricing, MarketBook.EMPTY, AuctionBook.EMPTY, listing, ben, 10, at()).orElseThrow();
        var market = MarketLedger.INSTANCE.apply(MarketBook.EMPTY, signal.withId(1));
        assertEquals(-1.0, market.state("minecraft:diamond").stock(), 1e-9, "a counted trade of ten draws the stock down by one");
    }

    @Test
    void theHouseComesBackFromTheRecord() {
        apply(list(ana, "minecraft:diamond", 10_000, 0, 24));
        apply(Auctions.bid(book, coins, S, ben, book.of(ana).getFirst().id(), 12_000, at()));
        apply(Auctions.order(coins, cy, "o-9", "minecraft:emerald", 4, 400, at()));
        var written = new ArrayList<WorldEvent>();
        for (int i = 0; i < record.size(); i++) written.add(record.get(i).withId(i + 1));
        assertEquals(book, Auctions.INSTANCE.replay(written));
        assertEquals(coins.balances(), Coins.INSTANCE.replay(written).balances().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private Auctions.Decision list(UUID seller, String key, long start, long buyNow, int hours) { return list(seller, key, start, buyNow, hours, 1); }

    private Auctions.Decision list(UUID seller, String key, long start, long buyNow, int hours, int count) {
        return Auctions.list(book, coins, S, seller, "l-" + (ids++), key, "{item}", count, start, buyNow, hours, at());
    }

    private void apply(Auctions.Decision decision) {
        assertTrue(decision.accepted(), decision.reason());
        for (var e : decision.events()) {
            record.add(e);
            book = Auctions.INSTANCE.apply(book, e);
            if (e.kind().equals(Coins.KIND)) coins = Coins.INSTANCE.apply(coins, e);
        }
    }

    private Factions.At at() { return new Factions.At(1, now, "w"); }
}
