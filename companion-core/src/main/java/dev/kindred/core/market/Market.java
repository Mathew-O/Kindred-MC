package dev.kindred.core.market;

import dev.kindred.core.factions.Coins;
import dev.kindred.core.factions.Factions;
import dev.kindred.core.record.WorldEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In plain terms: selling to the market and buying from it. A sale of many walks down the price one unit at a time
 * (the 2,304th cobblestone is not paid what the first was) and is written with its coins as one fact, so either both
 * the items and the coins move or neither does; a purchase walks up the same way and needs the coins first.
 *
 * Technical: Market decisions (M-06g), pure. {@link #quoteSale} canonicalises and prices without changing anything;
 * {@link #sell} returns the events of a settled sale: the {@code market.sale} with the roots' states after it, and the
 * seller's {@code coins} event, written together in one transaction by the adapter after it takes the items.
 */
public final class Market {
    private Market() {}

    /** A quote: what the stack is, what it would pay, or why it is refused. */
    public record Quote(Canonical.Key key, int count, long cents, Map<String, MarketBook.State> after, String refusal) {
        public boolean accepted() { return refusal == null; }

        static Quote refused(Canonical.Key key, String why) { return new Quote(key, 0, 0, Map.of(), why); }
    }

    /** What selling {@code facts} pays now, walking the curve a unit at a time. */
    public static Quote quoteSale(Pricing pricing, MarketBook book, ItemFacts facts, long nowMs) {
        var key = Canonical.of(facts, pricing.table());
        if (!key.accepted()) return Quote.refused(key, key.refusal());
        if (pricing.price(book, key.item(), nowMs).isEmpty()) return Quote.refused(key, "Valuation pending: " + key.item() + " has no price yet");
        long extra = pricing.enchantments(key.enchantments());
        double total = 0;
        MarketBook walking = book;
        var after = new LinkedHashMap<String, MarketBook.State>();
        for (int i = 0; i < facts.count(); i++) {
            var path = pricing.price(walking, key.item(), nowMs).orElseThrow();
            total += (path.mid() * key.wear() + extra) * (1 - Pricing.SPREAD);
            var moved = pricing.move(walking, path.roots(), key.wear(), nowMs);
            after.putAll(moved);
            walking = walking.with(moved);
        }
        // Rounded down once, so a floor below a hundredth of a coin still pays for many.
        return new Quote(key, facts.count(), (long) Math.floor(total), after, null);
    }

    /** What buying {@code count} of {@code item} costs now, walking up the curve; refused if it is not for sale. */
    public static Quote quoteBuy(Pricing pricing, MarketBook book, String item, int count, long nowMs) {
        String id = MarketTable.id(item);
        var key = new Canonical.Key(id, id, 1, Map.of(), "", null);
        if (count < 1 || count > 2304) return Quote.refused(key, "From 1 to 2,304 at a time");
        var own = pricing.own(book, id, nowMs);
        if (own.isEmpty()) return Quote.refused(key, "The market does not sell " + id);
        for (String root : own.get().roots().keySet()) {
            if (!pricing.root(book, root).orElseThrow().buyable()) return Quote.refused(key, "The market does not sell " + id + ": it only buys it");
        }
        double total = 0;
        MarketBook walking = book;
        var after = new LinkedHashMap<String, MarketBook.State>();
        for (int i = 0; i < count; i++) {
            var path = pricing.own(walking, id, nowMs).orElseThrow();
            total += path.mid() * (1 + Pricing.SPREAD);
            var moved = pricing.move(walking, path.roots(), -1, nowMs);
            after.putAll(moved);
            walking = walking.with(moved);
        }
        return new Quote(key, count, (long) Math.ceil(total), after, null);
    }

    /** A settled sale's events: the sale with its roots' states, and the seller's coins. */
    public static List<WorldEvent> sell(Quote quote, Coins.Book coins, UUID seller, String intent, Factions.At at) {
        if (!quote.accepted()) throw new IllegalArgumentException("Not a sale: " + quote.refusal());
        var data = new LinkedHashMap<String, String>();
        data.put("count", String.valueOf(quote.count()));
        data.put("cents", String.valueOf(quote.cents()));
        data.put("intent", intent);
        data.put("roots", MarketLedger.encode(quote.after()));
        var sale = WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), MarketLedger.SALE, seller, quote.key().key(), "", data);
        var paid = Coins.change(coins, seller, quote.cents(), "sold " + quote.count() + " " + quote.key().key(), at, "").orElseThrow();
        return List.of(sale, paid);
    }

    /** A settled purchase's events, or empty if the buyer cannot pay. */
    public static java.util.Optional<List<WorldEvent>> buy(Quote quote, Coins.Book coins, UUID buyer, Factions.At at) {
        if (!quote.accepted()) throw new IllegalArgumentException("Not a purchase: " + quote.refusal());
        var paid = Coins.change(coins, buyer, -quote.cents(), "bought " + quote.count() + " " + quote.key().key(), at, "");
        if (paid.isEmpty()) return java.util.Optional.empty();
        var data = new LinkedHashMap<String, String>();
        data.put("count", String.valueOf(quote.count()));
        data.put("cents", String.valueOf(quote.cents()));
        data.put("roots", MarketLedger.encode(quote.after()));
        var bought = WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), MarketLedger.BUY, buyer, quote.key().key(), "", data);
        return java.util.Optional.of(List.of(bought, paid.get()));
    }

    /** An operator's baseline for {@code item}: it is priced from now on (a pending item made sellable). */
    public static WorldEvent baseline(MarketTable.Root root, UUID by, Factions.At at) {
        var data = new LinkedHashMap<String, String>();
        data.put("cents", String.valueOf(root.baseline()));
        data.put("volatility", root.volatility().name());
        data.put("buyable", String.valueOf(root.buyable()));
        data.put("by", by == null ? "console" : by.toString());
        return WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), MarketLedger.BASELINE, by, root.item(), "", data);
    }

    /** The mid price change of {@code root} over the last day, as a share (0.1 for up a tenth). */
    public static double dayChange(Pricing pricing, MarketBook book, String root, long nowMs) {
        var r = pricing.root(book, root).orElseThrow();
        double now = Pricing.mid(r, book.state(root), nowMs);
        double then = Pricing.mid(r, book.stateAt(root, nowMs - 24 * Pricing.HOUR_MS), nowMs - 24 * Pricing.HOUR_MS);
        return then <= 0 ? 0 : now / then - 1;
    }
}
