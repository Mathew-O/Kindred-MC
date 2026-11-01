package dev.kindred.core.guide;

import dev.kindred.core.guide.Trades.Enchant;
import dev.kindred.core.guide.Trades.Goods;
import dev.kindred.core.guide.Trades.Offer;
import dev.kindred.core.guide.Trades.Profession;
import dev.kindred.core.team.ShareOut.Material;
import dev.kindred.core.team.ShareOut.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how a player trades with villagers: an emerald is valued at what the cheapest thing
 * it can sell costs to gather, a purchase is made only when it is worth more to the player than it costs, it
 * sells only what it can spare and never what its run needs, it knows what each kind of villager trades, and
 * it keeps a librarian with a book worth having, otherwise resetting its offers a limited number of times.
 */
class TradesTest {
    private static final Worth.Holding NOTHING = Worth.Holding.of(Map.of(), Map.of());

    private static Offer income(String item, int count, int maxUses) {
        return new Offer(new Goods(item, count), new Goods(Trades.EMERALD, 1), maxUses);
    }

    private static Offer sale(int emeralds, String item, Enchant... enchants) {
        return new Offer(new Goods(Trades.EMERALD, emeralds), null, new Goods(item, 1), List.of(enchants), 0, 12);
    }

    private static Offer book(int emeralds, Enchant... enchants) {
        return new Offer(new Goods(Trades.EMERALD, emeralds), new Goods("book", 1), new Goods("enchanted_book", 1), List.of(enchants), 0, 12);
    }

    @Test
    void anEmeraldCostsWhatTheCheapestIncomeItCanStillMakeCostsToGather() {
        var wheat = income("wheat", 20, 16);
        var sticks = income("stick", 32, 16);
        assertTrue(wheat.income() && !sale(5, "ender_pearl").income());
        assertEquals(20 * 0.02, Trades.emeraldPrice(List.of(wheat)), 1e-9, "Twenty wheat from its field");
        assertEquals(32 * 0.01, Trades.emeraldPrice(List.of(wheat, sticks)), 1e-9, "Thirty-two sticks are cheaper");
        var soldOut = new Offer(new Goods("stick", 32), null, new Goods(Trades.EMERALD, 1), List.of(), 16, 16);
        assertEquals(20 * 0.02, Trades.emeraldPrice(List.of(wheat, soldOut)), 1e-9, "A trade sold out until the restock is none");
        assertEquals(Trades.EMERALD_MINED, Trades.emeraldPrice(List.of(sale(5, "ender_pearl"))), 1e-9, "No income known: an emerald mined");
        assertEquals(Trades.EMERALD_MINED, Trades.emeraldPrice(List.of(income("diamond", 1, 12))), 1e-9,
                "Selling a diamond for an emerald is no cheaper than mining one");
    }

    @Test
    void aPurchaseIsWorthItWhenWhatItGetsIsWorthMoreToItThanItCosts() {
        double emerald = 0.32;
        var mending = Trades.judge(book(10, new Enchant("mending", 1)), NOTHING, emerald);
        assertTrue(mending.worthIt() && mending.profit() > 30, "Mending for ten emeralds: " + mending);
        assertEquals("A Mending book for 10 emeralds: worth it.", mending.why());
        var weak = Trades.judge(book(30, new Enchant("protection", 1)), NOTHING, emerald);
        assertFalse(weak.worthIt(), "Protection I for thirty: " + weak);
        assertEquals("A Protection I book for 30 emeralds: not worth it to me.", weak.why());
        assertTrue(Trades.judge(book(20, new Enchant("efficiency", 5)), NOTHING, emerald).worthIt(), "Efficiency V");
        assertEquals("An Efficiency V book for 20 emeralds: worth it.", Trades.judge(book(20, new Enchant("efficiency", 5)), NOTHING, emerald).why());
        // Kit weighed against its own: diamond better than iron, a spare against diamond.
        var chestplate = sale(21, "diamond_chestplate", new Enchant("protection", 2));
        var inIron = Worth.Holding.of(Map.of(), Worth.kit(Map.of(Slot.CHESTPLATE, Material.IRON)));
        var inDiamond = Worth.Holding.of(Map.of(), Map.of(Slot.CHESTPLATE, new Worth.Piece(Material.DIAMOND, 4)));
        assertTrue(Trades.judge(chestplate, inIron, emerald).worthIt(), "Better than its iron: " + Trades.judge(chestplate, inIron, emerald));
        assertTrue(Trades.judge(chestplate, inIron, emerald).profit() > Trades.judge(chestplate, inDiamond, emerald).profit(),
                "Against its own enchanted diamond, a spare: " + Trades.judge(chestplate, inDiamond, emerald));
        // Ender pearls: worth five emeralds to a run short of them; a curse never.
        var pearl = sale(5, "ender_pearl");
        var shortOfPearls = new Worth.Holding(Map.of(), Map.of(), Map.of("minecraft:ender_pearl", 12));
        assertTrue(Trades.judge(pearl, shortOfPearls, emerald).worthIt(), "Pearls: " + Trades.judge(pearl, shortOfPearls, emerald));
        assertFalse(Trades.judge(book(5, new Enchant("binding_curse", 1)), NOTHING, emerald).worthIt());
        assertTrue(Trades.bookWorth(List.of(new Enchant("unbreaking", 3))) > 8 * Trades.bookWorth(List.of(new Enchant("unbreaking", 1))),
                "The top level is what is worth having");
    }

    @Test
    void itSellsWhatItCanSpareForWhatTheEmeraldsBuyCheapestFirstAndNeverWhatItsRunWants() {
        var wheat = income("wheat", 20, 16);
        var paper = income("paper", 24, 16);
        var mending = book(12, new Enchant("mending", 1));
        var bread = new Offer(new Goods(Trades.EMERALD, 1), null, new Goods("bread", 6), List.of(), 0, 16);
        var barn = new Worth.Holding(Map.of("minecraft:wheat", 200, "minecraft:paper", 48, "minecraft:book", 1), Map.of(), Map.of());
        var plan = Trades.plan(List.of(wheat, paper, mending, bread), barn, 2);
        assertEquals(1, plan.buy().size(), "Mending; bread for an emerald is no better than its own wheat: " + plan);
        assertEquals(mending, plan.buy().getFirst().deal().offer());
        // Twelve emeralds less the two it holds: ten to earn, wheat cheaper an emerald than paper.
        assertEquals(1, plan.sell().size(), plan.toString());
        assertEquals(wheat, plan.sell().getFirst().deal().offer());
        assertEquals(10, plan.sell().getFirst().times());
        assertEquals("I can spare 20 wheat for an emerald, and they grow back.", plan.sell().getFirst().deal().why());
        // What its run wants is not for sale: with its wheat wanted, what it pays with is paper, and forty-eight sheets
        // are two emeralds, not ten: the book is beyond it, and nothing is sold for nothing.
        var wanted = new Worth.Holding(barn.held(), Map.of(), Map.of("minecraft:wheat", 200));
        assertTrue(Trades.plan(List.of(wheat, paper, mending), wanted, 2).empty(), "Not paid for: nothing");
        var papered = new Worth.Holding(Map.of("minecraft:wheat", 200, "minecraft:paper", 240, "minecraft:book", 1), Map.of(),
                Map.of("minecraft:wheat", 200));
        var other = Trades.plan(List.of(wheat, paper, mending), papered, 2);
        assertEquals(paper, other.sell().getFirst().deal().offer(), other.toString());
        assertEquals(10, other.sell().getFirst().times(), "Ten trades of paper for the ten emeralds");
        // With no book to give for it, the enchanted book is not for it; nothing worth buying, nothing sold.
        var noBook = new Worth.Holding(Map.of("minecraft:wheat", 200), Map.of(), Map.of());
        assertTrue(Trades.plan(List.of(wheat, mending), noBook, 2).empty(), "No book in hand");
        assertTrue(Trades.plan(List.of(wheat, bread), barn, 0).empty());
        // What it made, in words.
        assertEquals("I sold 200 wheat for 10 emeralds and bought a Mending book for 12 emeralds and a book.",
                Trades.told(List.of(Trades.made(wheat, 10), Trades.made(mending, 1))));
        assertEquals("I bought 2 ender pearls for 10 emeralds.", Trades.told(List.of(Trades.made(sale(5, "ender_pearl"), 2))));
    }

    @Test
    void itKnowsWhatEachProfessionBuysAndSellsAndWhomToLookForWhileIncomeFromWhatGrowsBackLasts() {
        assertEquals("minecraft:lectern", Profession.LIBRARIAN.jobBlock());
        assertEquals("minecraft:brewing_stand", Profession.CLERIC.jobBlock());
        assertNull(Profession.NITWIT.jobBlock());
        assertEquals(Profession.FLETCHER, Profession.of("minecraft:fletcher"));
        assertTrue(Trades.expected(Profession.NITWIT, 5).isEmpty(), "A nitwit trades nothing");
        var pearls = Trades.sellers("ender_pearl");
        assertEquals(1, pearls.size());
        assertEquals(Profession.CLERIC, pearls.getFirst().profession());
        assertEquals(4, pearls.getFirst().level(), "An expert cleric");
        assertTrue(Trades.expected(Profession.CLERIC, 3).stream().noneMatch(t -> t.offer().result().item().equals("minecraft:ender_pearl")),
                "Not before expert");
        assertTrue(Trades.expected(Profession.LIBRARIAN, 1).stream().anyMatch(t -> t.offer().result().item().equals("minecraft:enchanted_book")));
        // A farmer's novice trades are all crops: income that lasts, sixteen of each, restocked twice a day.
        var farmer = Trades.expected(Profession.FARMER, 1).stream().map(Trades.Typical::offer).toList();
        assertEquals(4 * 16 * 2, Trades.renewableIncome(farmer));
        var armorer = Trades.expected(Profession.ARMORER, 2).stream().map(Trades.Typical::offer).toList();
        assertEquals(0, Trades.renewableIncome(armorer), "Coal and iron are dug, not grown");
        assertTrue(Trades.renewable("wheat") && Trades.renewable("minecraft:rotten_flesh") && !Trades.renewable("iron_ingot"));
        // Whom to keep: a librarian with Mending is worth far more than one without, a nitwit nothing.
        var withMending = List.of(income("paper", 24, 16), book(12, new Enchant("mending", 1)));
        var without = List.of(income("paper", 24, 16), book(40, new Enchant("bane_of_arthropods", 2)));
        assertTrue(Trades.keepWorth(withMending, NOTHING) > 10 * Trades.keepWorth(without, NOTHING),
                Trades.keepWorth(withMending, NOTHING) + " against " + Trades.keepWorth(without, NOTHING));
        assertEquals(0, Trades.keepWorth(List.of(), NOTHING));
    }

    @Test
    void aLibrarianWithABookWorthKeepingIsKeptOtherwiseRerolledUntilItGivesUp() {
        var mending = List.of(income("paper", 24, 16), book(12, new Enchant("mending", 1)));
        var bane = List.of(income("paper", 24, 16), book(40, new Enchant("bane_of_arthropods", 2)));
        assertTrue(Trades.worthKeeping(mending, NOTHING));
        assertFalse(Trades.worthKeeping(bane, NOTHING));
        assertEquals(Trades.Reroll.KEEP, Trades.reroll(mending, NOTHING, 0));
        assertEquals(Trades.Reroll.REROLL, Trades.reroll(bane, NOTHING, 0));
        assertEquals(Trades.Reroll.REROLL, Trades.reroll(bane, NOTHING, Trades.MAX_REROLLS - 1));
        assertEquals(Trades.Reroll.GIVE_UP, Trades.reroll(bane, NOTHING, Trades.MAX_REROLLS));
        // A farmer's crops alone, income that lasts, are not a cell's worth; an expert cleric's pearls for a run short of them are.
        var farmer = Trades.expected(Profession.FARMER, 1).stream().map(Trades.Typical::offer).toList();
        assertFalse(Trades.worthKeeping(farmer, NOTHING));
        var cleric = Trades.expected(Profession.CLERIC, 4).stream().map(Trades.Typical::offer).toList();
        var shortOfPearls = new Worth.Holding(Map.of(), Map.of(), Map.of("minecraft:ender_pearl", 12));
        assertTrue(Trades.keepWorth(cleric, shortOfPearls) > Trades.keepWorth(cleric, NOTHING));
    }
}
