package dev.kindred.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.factions.Coins;
import dev.kindred.core.factions.Factions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the adaptive market (M-06g): the price curve and its classes, floors and ceilings, the hour's
 * limit and the drift back to the baseline; the canonicaliser's edge cases (damaged tools, enchanted books, potions,
 * renamed items, filled maps, suspicious stew, containers, admin items, unknown data); a bulk sale walking the curve;
 * purchases; prices of made things from their recipes; and the market rebuilt from the World Record.
 */
class MarketTest {
    private static final long HOUR = 3_600_000;
    private final MarketTable table = MarketTable.bundled();
    private final List<Recipe> recipes = List.of(
            new Recipe("stick", "minecraft:stick", 4, List.of(Set.of("minecraft:oak_planks"), Set.of("minecraft:oak_planks"))),
            new Recipe("planks", "minecraft:oak_planks", 4, List.of(Set.of("minecraft:oak_log"))),
            new Recipe("pick", "minecraft:iron_pickaxe", 1, List.of(Set.of("minecraft:iron_ingot"), Set.of("minecraft:iron_ingot"), Set.of("minecraft:iron_ingot"),
                    Set.of("minecraft:stick"), Set.of("minecraft:stick"))),
            new Recipe("book", "minecraft:book", 1, List.of(Set.of("minecraft:paper"), Set.of("minecraft:paper"), Set.of("minecraft:paper"), Set.of("minecraft:leather"))),
            new Recipe("paper", "minecraft:paper", 3, List.of(Set.of("minecraft:sugar_cane"), Set.of("minecraft:sugar_cane"), Set.of("minecraft:sugar_cane"))),
            new Recipe("enchanted_book", "minecraft:enchanted_book", 1, List.of(Set.of("minecraft:book"))));
    private final Pricing pricing = new Pricing(table, recipes);
    private final long now = 100 * HOUR;

    @Test
    void theCurveFallsAsTheMarketFillsWithinItsFloorAndCeiling() {
        var iron = table.roots().get("minecraft:iron_ingot");
        assertEquals(600, pricing.rootMid(MarketBook.EMPTY, iron, now), 1e-9, "at balance, the baseline");
        var glut = MarketBook.EMPTY.with(Map.of(iron.item(), new MarketBook.State(10_000, now, 0, 0)));
        assertEquals(600 * MarketTable.Volatility.ORDINARY.floor, pricing.rootMid(glut, iron, now), 1e-9, "never below its floor");
        var shortage = MarketBook.EMPTY.with(Map.of(iron.item(), new MarketBook.State(-10_000, now, 0, 0)));
        assertEquals(600 * MarketTable.Volatility.ORDINARY.ceiling, pricing.rootMid(shortage, iron, now), 1e-9, "never above its ceiling");
        assertTrue(MarketTable.Volatility.MASS.sensitivity < MarketTable.Volatility.ORDINARY.sensitivity
                && MarketTable.Volatility.ORDINARY.sensitivity < MarketTable.Volatility.SCARCE.sensitivity, "mass-farmable moves slowest");
    }

    @Test
    void anIdleStockDriftsBackAndAnHourLimitsTheMove() {
        var iron = table.roots().get("minecraft:iron_ingot");
        var sold = MarketBook.EMPTY.with(Map.of(iron.item(), new MarketBook.State(300, now, 0, 0)));
        double at = pricing.rootMid(sold, iron, now);
        double later = pricing.rootMid(sold, iron, now + 6 * HOUR);
        assertTrue(later > at && later < 600, "half way back after a half-life: " + at + " -> " + later);
        assertEquals(600 * Math.exp(-0.002 * 150), later, 1e-6);
        // Within the hour the mid stays within a fifth of where it stood an hour before.
        var anchored = MarketBook.EMPTY.with(Map.of(iron.item(), new MarketBook.State(1_000, now, 600, now)));
        assertEquals(600 * (1 - Pricing.MAX_HOURLY), pricing.rootMid(anchored, iron, now), 1e-9);
        assertTrue(pricing.rootMid(anchored, iron, now + HOUR) < 480, "free again an hour on");
    }

    @Test
    void aBulkSaleWalksTheCurveAndCostsTheNextSeller() {
        var stack = ItemFacts.plain("minecraft:cobblestone", 2304);
        var quote = Market.quoteSale(pricing, MarketBook.EMPTY, stack, now);
        assertTrue(quote.accepted());
        long flat = (long) Math.floor(2304 * 2 * (1 - Pricing.SPREAD));
        assertTrue(quote.cents() < flat, "2,304 are not all paid the first one's price: " + quote.cents() + " < " + flat);
        var after = MarketBook.EMPTY.with(quote.after());
        var next = Market.quoteSale(pricing, after, ItemFacts.plain("minecraft:cobblestone", 1), now);
        var first = Market.quoteSale(pricing, MarketBook.EMPTY, ItemFacts.plain("minecraft:cobblestone", 64), now);
        var again = Market.quoteSale(pricing, after, ItemFacts.plain("minecraft:cobblestone", 64), now);
        assertTrue(again.cents() < first.cents(), "the next seller is paid less: " + again.cents() + " < " + first.cents());
        assertEquals(2304, after.state("minecraft:cobblestone").stock(), 1e-9);
        assertTrue(next.accepted());
    }

    @Test
    void thingsMadeArePricedFromWhatTheyAreMadeOf() {
        var sticks = pricing.price(MarketBook.EMPTY, "minecraft:stick", now).orElseThrow();
        assertEquals(2 * (50 / 4.0) / 4, sticks.mid(), 1e-9, "a stick is the lesser of its own price (10) and two planks' quarter (6.25)");
        var pick = pricing.price(MarketBook.EMPTY, "minecraft:iron_pickaxe", now).orElseThrow();
        assertEquals(3 * 600 + 2 * 6.25, pick.mid(), 1e-9);
        assertEquals(3.0, pick.roots().get("minecraft:iron_ingot"), 1e-9);
        assertTrue(pricing.price(MarketBook.EMPTY, "minecraft:beacon", now).isEmpty(), "no recipe from priced things and no baseline: pending");
        // A block of iron is nine ingots and moves the ingots' price.
        var ingot = Market.quoteSale(pricing, MarketBook.EMPTY, ItemFacts.plain("minecraft:iron_ingot", 16), now);
        assertEquals(16.0, ingot.after().get("minecraft:iron_ingot").stock(), 0, "sixteen ingots are exactly sixteen, not nine nuggets' worth");
        var block = Market.quoteSale(pricing, MarketBook.EMPTY, ItemFacts.plain("minecraft:iron_block", 1), now);
        assertEquals(9.0, block.after().get("minecraft:iron_ingot").stock(), 1e-9);
    }

    @Test
    void theCanonicaliserKeysOnlyWhatIsValued() {
        var named = new ItemFacts("minecraft:diamond", 1, 0, 0, Map.of(), "", false, Set.of());
        assertEquals("minecraft:diamond", Canonical.of(named, table).key(), "a renamed item is the item");
        var worn = new ItemFacts("minecraft:iron_pickaxe", 1, 150, 250, Map.of(), "", false, Set.of());
        assertEquals("minecraft:iron_pickaxe@25", Canonical.of(worn, table).key(), "two fifths left: the quarter band below it");
        assertEquals("minecraft:iron_pickaxe@10", Canonical.of(new ItemFacts("minecraft:iron_pickaxe", 1, 200, 250, Map.of(), "", false, Set.of()), table).key(),
                "a fifth left: below a quarter, a tenth");
        var fresh = Market.quoteSale(pricing, MarketBook.EMPTY, ItemFacts.plain("minecraft:iron_pickaxe", 1), now);
        var damaged = Market.quoteSale(pricing, MarketBook.EMPTY, worn, now);
        assertTrue(damaged.cents() < fresh.cents() / 3, "a damaged tool is not sold as pristine: " + damaged.cents() + " of " + fresh.cents());
        var book = new ItemFacts("minecraft:enchanted_book", 1, 0, 0, Map.of("minecraft:mending", 1), "", false, Set.of());
        assertEquals("minecraft:enchanted_book{minecraft:mending=1}", Canonical.of(book, table).key());
        assertTrue(Market.quoteSale(pricing, MarketBook.EMPTY, book, now).cents() > Market.quoteSale(pricing, MarketBook.EMPTY,
                ItemFacts.plain("minecraft:book", 1), now).cents(), "mending carries value");
        var unknownEnchant = new ItemFacts("minecraft:enchanted_book", 1, 0, 0, Map.of("othermod:zap", 3), "", false, Set.of());
        assertTrue(Canonical.of(unknownEnchant, table).refusal().startsWith("Valuation pending"));
        var potion = new ItemFacts("minecraft:potion", 1, 0, 0, Map.of(), "minecraft:swiftness", false, Set.of());
        assertEquals("minecraft:potion#minecraft:swiftness", Canonical.of(potion, table).key());
        var pendingPotion = Market.quoteSale(pricing, MarketBook.EMPTY, potion, now);
        assertEquals("Valuation pending: minecraft:potion#minecraft:swiftness has no price yet", pendingPotion.refusal());
        assertEquals("minecraft:filled_map", Canonical.of(ItemFacts.plain("minecraft:filled_map", 1), table).key(), "a map's drawing is not valued");
        assertEquals("minecraft:suspicious_stew", Canonical.of(ItemFacts.plain("minecraft:suspicious_stew", 1), table).key(), "nor a stew's effect");
        var shulker = new ItemFacts("minecraft:shulker_box", 1, 0, 0, Map.of(), "", true, Set.of());
        assertEquals("A container with things in it: empty it first", Canonical.of(shulker, table).refusal());
        assertEquals("Never paid for: an admin-only item", Canonical.of(ItemFacts.plain("minecraft:command_block", 1), table).refusal());
        assertEquals("Never paid for: an admin-only item", Canonical.of(ItemFacts.plain("minecraft:zombie_spawn_egg", 1), table).refusal());
        var unknownData = new ItemFacts("minecraft:diamond_sword", 1, 0, 0, Map.of(), "", false, Set.of("minecraft:unbreakable"));
        assertEquals("Valuation pending: data the market does not know (minecraft:unbreakable)", Canonical.of(unknownData, table).refusal());
    }

    @Test
    void purchasesWalkUpAndOnlyRawMaterialsTheMarketStocksAreSold() {
        var buy = Market.quoteBuy(pricing, MarketBook.EMPTY, "minecraft:iron_ingot", 10, now);
        assertTrue(buy.accepted());
        assertTrue(buy.cents() > (long) Math.ceil(10 * 600 * (1 + Pricing.SPREAD)), "each one dearer than the last");
        assertEquals("The market does not sell minecraft:diamond: it only buys it", Market.quoteBuy(pricing, MarketBook.EMPTY, "minecraft:diamond", 1, now).refusal());
        assertEquals("The market does not sell minecraft:iron_pickaxe", Market.quoteBuy(pricing, MarketBook.EMPTY, "minecraft:iron_pickaxe", 1, now).refusal());
        UUID buyer = UUID.randomUUID();
        var at = new Factions.At(1, now, "w");
        assertTrue(Market.buy(buy, new Coins.Book(Map.of(buyer, 100L), 0), buyer, at).isEmpty(), "not without the coins");
        var events = Market.buy(buy, new Coins.Book(Map.of(buyer, 1_000_000L), 0), buyer, at).orElseThrow();
        assertEquals(List.of(MarketLedger.BUY, Coins.KIND), events.stream().map(e -> e.kind()).toList());
    }

    @Test
    void aSaleAndItsCoinsAreOneFactAndTheMarketComesBackFromTheRecord() {
        UUID seller = UUID.randomUUID();
        var at = new Factions.At(1, now, "w");
        var quote = Market.quoteSale(pricing, MarketBook.EMPTY, ItemFacts.plain("minecraft:iron_ingot", 32), now);
        var events = Market.sell(quote, new Coins.Book(Map.of(seller, 50_000L), 50_000), seller, "intent-1", at);
        assertEquals(List.of(MarketLedger.SALE, Coins.KIND), events.stream().map(e -> e.kind()).toList());
        var written = new ArrayList<dev.kindred.core.record.WorldEvent>();
        for (int i = 0; i < events.size(); i++) written.add(events.get(i).withId(i + 1));
        var book = MarketLedger.INSTANCE.replay(written);
        assertEquals(quote.after().get("minecraft:iron_ingot"), book.state("minecraft:iron_ingot"));
        assertEquals(50_000 + quote.cents(), Coins.INSTANCE.replay(written).of(seller));
        var base = Market.baseline(new MarketTable.Root("minecraft:beacon", 50_000, MarketTable.Volatility.SCARCE, false), null, at).withId(3);
        var withBeacon = MarketLedger.INSTANCE.apply(book, base);
        assertTrue(pricing.price(withBeacon, "minecraft:beacon", now).isPresent(), "a pending item priced once its baseline is set");
    }
}
