package dev.kindred.core.guide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a villager's trade is worth (E-45a), reckoned as a player who knows the game reckons it. The owner's direction
 * (2026-10-25): a player understands what is valuable and how to profit by villagers. Emeralds are the currency. A
 * player earns them by selling a villager what it buys (wheat to a farmer, sticks to a fletcher, paper to a librarian),
 * and an emerald costs it what the cheapest of those it can feed costs to gather. It spends them on what is worth more
 * to it than they cost: a Mending book, diamond kit better than its own, ender pearls its run is short of. What a trade
 * is worth to it follows from {@link Worth}, in minutes of play, against what it holds and wears.
 *
 * <p>What each profession buys and sells, by level, is vanilla's own table as a player learns it (the wiki's), so that it
 * knows what to look for and whom; what a particular villager offers it knows only from its trading screen, opened as a
 * player opens it. Its prices there are the villager's own, discounts and demand included. Pure.
 */
public final class Trades {
    private Trades() { }

    public static final String EMERALD = "minecraft:emerald";

    /** A villager's profession, as its clothes and its job block show it. */
    public enum Profession {
        ARMORER("blast_furnace"), BUTCHER("smoker"), CARTOGRAPHER("cartography_table"), CLERIC("brewing_stand"), FARMER("composter"),
        FISHERMAN("barrel"), FLETCHER("fletching_table"), LEATHERWORKER("cauldron"), LIBRARIAN("lectern"), MASON("stonecutter"),
        SHEPHERD("loom"), TOOLSMITH("smithing_table"), WEAPONSMITH("grindstone"), NITWIT(null), NONE(null);

        private final String jobBlock;

        Profession(String jobBlock) { this.jobBlock = jobBlock == null ? null : "minecraft:" + jobBlock; }

        /** The block that gives a villager this profession, and the one it works at; none for a nitwit or the jobless. */
        public String jobBlock() { return jobBlock; }

        /** A profession by its id, "minecraft:librarian", or {@link #NONE}. */
        public static Profession of(String id) {
            String path = id.substring(id.indexOf(':') + 1).toUpperCase(Locale.ROOT);
            for (Profession each : values()) if (each.name().equals(path)) return each;
            return NONE;
        }
    }

    /** One side of a trade: an item, and how many. */
    public record Goods(String item, int count) {
        public Goods {
            item = Trades.id(item);
            if (count <= 0) throw new IllegalArgumentException("Goods are something");
        }
    }

    /** An enchantment and its level, as a book's or a piece's tooltip shows it. */
    public record Enchant(String id, int level) {
        public Enchant {
            id = Trades.id(id);
            if (level <= 0) throw new IllegalArgumentException("A level is at least one");
        }
    }

    /**
     * An offer as the trading screen shows it: what the villager asks (one stack, or two), what it gives and the
     * enchantments that carries, and how many times it can be made before the villager restocks, of how many.
     */
    public record Offer(Goods first, Goods second, Goods result, List<Enchant> enchants, int uses, int maxUses) {
        public Offer {
            Objects.requireNonNull(first);
            Objects.requireNonNull(result);
            enchants = List.copyOf(enchants);
            if (uses < 0 || maxUses <= 0 || uses > maxUses) throw new IllegalArgumentException("Uses out of range");
        }

        public Offer(Goods first, Goods result, int maxUses) { this(first, null, result, List.of(), 0, maxUses); }

        /** The villager pays emeralds for what it buys: income for the player. */
        public boolean income() {
            return result.item().equals(EMERALD) && !first.item().equals(EMERALD) && (second == null || !second.item().equals(EMERALD));
        }

        /** How many emeralds the player pays. */
        public int emeralds() {
            return (first.item().equals(EMERALD) ? first.count() : 0) + (second != null && second.item().equals(EMERALD) ? second.count() : 0);
        }

        /** Whether it can still be made before the villager restocks. */
        public boolean open() { return uses < maxUses; }

        /** How many more times. */
        public int left() { return maxUses - uses; }
    }

    // ------------------------------------------------------------------ what goods cost to gather

    /**
     * Minutes to gather one of what villagers buy, from what a player sets up for it: a field, a sugar cane patch, a
     * tree. Others are {@link Worth}'s minutes to get one otherwise.
     */
    private static final Map<String, Double> GATHER = new HashMap<>();
    /** What a player can grow, breed, farm or cut again: income from it lasts. */
    private static final Set<String> RENEWABLE;

    static {
        for (var each : new Object[][] {
                {"wheat", 0.02}, {"potato", 0.01}, {"carrot", 0.01}, {"beetroot", 0.02}, {"pumpkin", 0.05}, {"melon", 0.03},
                {"paper", 0.03}, {"stick", 0.01}, {"sugar_cane", 0.01}, {"feather", 0.05}, {"leather", 0.15}, {"clay_ball", 0.02},
                {"glass_pane", 0.02}, {"glass_bottle", 0.04}, {"ink_sac", 0.08}, {"book", 0.1}, {"writable_book", 0.2},
                {"nether_wart", 0.02}, {"chicken", 0.15}, {"porkchop", 0.15}, {"mutton", 0.15}, {"rabbit", 0.2}, {"cod", 0.08},
                {"salmon", 0.1}, {"tropical_fish", 0.15}, {"pufferfish", 0.15}, {"sweet_berries", 0.03}, {"dried_kelp_block", 0.05},
                {"tripwire_hook", 0.3}, {"rabbit_hide", 0.2}, {"rabbit_foot", 0.5}, {"turtle_scute", 1.0}, {"quartz", 0.1},
                {"gravel", 0.01}, {"flint", 0.05},
                // Food made of what it grows: three wheat a loaf, a potato in the furnace, meat it breeds.
                {"bread", 0.06}, {"baked_potato", 0.02}, {"cookie", 0.02}, {"pumpkin_pie", 0.1}, {"cooked_porkchop", 0.2},
                {"cooked_chicken", 0.2}, {"cooked_beef", 0.2}, {"cooked_mutton", 0.2}}) {
            GATHER.put(id((String) each[0]), (Double) each[1]);
        }
        RENEWABLE = Set.of(id("wheat"), id("potato"), id("carrot"), id("beetroot"), id("pumpkin"), id("melon"), id("paper"), id("stick"),
                id("sugar_cane"), id("feather"), id("leather"), id("book"), id("nether_wart"), id("chicken"), id("porkchop"), id("mutton"),
                id("rabbit"), id("cod"), id("salmon"), id("tropical_fish"), id("pufferfish"), id("sweet_berries"), id("dried_kelp_block"),
                id("rotten_flesh"), id("string"), id("white_wool"), id("ink_sac"), id("glass_bottle"), id("rabbit_hide"), id("gravel"), id("flint"));
    }

    /** Minutes to gather one of {@code item}: what a player sets up for it, else what it is worth otherwise. */
    public static double gather(String item) {
        Double set = GATHER.get(id(item));
        if (set != null) return set;
        return Worth.judge(new Worth.Stack(id(item), 1), Worth.Holding.of(Map.of(), Map.of())).worth();
    }

    /** Whether a player can keep gathering it: grown, bred, farmed or cut again. */
    public static boolean renewable(String item) {
        return RENEWABLE.contains(id(item));
    }

    // ------------------------------------------------------------------ the emerald

    /** An emerald's price when it knows no trade to earn one by: Worth's minutes to mine one. */
    static final double EMERALD_MINED = 0.5;

    /**
     * What an emerald costs the player, in minutes: the cheapest income among {@code offers} it can still make, what it
     * would gather for one emerald. With none, an emerald mined.
     */
    public static double emeraldPrice(List<Offer> offers) {
        double best = EMERALD_MINED;
        for (Offer offer : offers) {
            if (!offer.income() || !offer.open()) continue;
            best = Math.min(best, cost(offer, EMERALD_MINED) / offer.result().count());
        }
        return best;
    }

    /** What the player gives for an offer, in minutes: its emeralds at {@code emerald} each and the goods at their gathering. */
    static double cost(Offer offer, double emerald) {
        double total = side(offer.first(), emerald);
        if (offer.second() != null) total += side(offer.second(), emerald);
        return total;
    }

    private static double side(Goods goods, double emerald) {
        return goods.item().equals(EMERALD) ? goods.count() * emerald : goods.count() * gather(goods.item());
    }

    // ------------------------------------------------------------------ what a purchase is worth

    /** What an enchantment at its highest level is worth on a book, in minutes, and that level. */
    private record Value(double minutes, int max) { }

    private static final Map<String, Value> ENCHANTS = new HashMap<>();

    static {
        for (var each : new Object[][] {
                {"mending", 40.0, 1}, {"unbreaking", 10.0, 3}, {"efficiency", 10.0, 5}, {"fortune", 12.0, 3}, {"silk_touch", 6.0, 1},
                {"protection", 10.0, 4}, {"feather_falling", 8.0, 4}, {"sharpness", 10.0, 5}, {"looting", 8.0, 3}, {"power", 6.0, 5},
                {"infinity", 6.0, 1}, {"fire_aspect", 4.0, 2}, {"sweeping_edge", 3.0, 3}, {"blast_protection", 4.0, 4},
                {"fire_protection", 3.0, 4}, {"projectile_protection", 3.0, 4}, {"respiration", 3.0, 3}, {"aqua_affinity", 3.0, 1},
                {"depth_strider", 4.0, 3}, {"frost_walker", 2.0, 2}, {"thorns", 2.0, 3}, {"smite", 3.0, 5}, {"bane_of_arthropods", 1.0, 5},
                {"knockback", 1.0, 2}, {"punch", 1.0, 2}, {"flame", 2.0, 1}, {"quick_charge", 4.0, 3}, {"multishot", 2.0, 1},
                {"piercing", 2.0, 4}, {"loyalty", 3.0, 3}, {"riptide", 2.0, 3}, {"channeling", 2.0, 1}, {"impaling", 2.0, 5},
                {"lure", 2.0, 3}, {"luck_of_the_sea", 2.0, 3}, {"binding_curse", -5.0, 1}, {"vanishing_curse", -5.0, 1}}) {
            ENCHANTS.put(id((String) each[0]), new Value((Double) each[1], (Integer) each[2]));
        }
    }

    /** What a book with these enchantments is worth: each at its level's share of its highest, the lower levels much less. */
    public static double bookWorth(List<Enchant> enchants) {
        double total = 0;
        for (Enchant enchant : enchants) {
            Value value = ENCHANTS.get(enchant.id());
            if (value == null) continue;
            double share = Math.min(1.0, (double) enchant.level() / value.max());
            total += value.minutes() * share * share;
        }
        return total;
    }

    /** What the player gets from an offer, in minutes, as it stands: a book by its enchantments, the rest by {@link Worth}. */
    public static double gain(Offer offer, Worth.Holding holding) {
        Goods result = offer.result();
        if (result.item().equals(id("enchanted_book"))) return bookWorth(offer.enchants()) * result.count();
        int levels = offer.enchants().stream().mapToInt(Enchant::level).sum();
        double worth = Worth.judge(new Worth.Stack(result.item(), result.count(), levels), holding).worth();
        // What it can grow or make of what it grows is worth no more than that costs it.
        Double own = GATHER.get(result.item());
        if (own != null) worth = Math.min(worth, own * result.count());
        // An enchantment it can read is worth what it is: Mending on a chestplate is Mending.
        return worth + 0.5 * bookWorth(offer.enchants());
    }

    /** A trade weighed: income or a purchase, what it costs and gains in minutes, the profit, and why, in words it can say. */
    public record Deal(Offer offer, boolean income, double cost, double gain, String why) {
        public double profit() { return gain - cost; }

        public boolean worthIt() { return profit() > 0; }
    }

    /**
     * Whether a purchase is worth making at {@code emerald} minutes an emerald, as it stands; an income trade is worth
     * making with goods it can spare, for what the emeralds buy.
     */
    public static Deal judge(Offer offer, Worth.Holding holding, double emerald) {
        Objects.requireNonNull(offer);
        Objects.requireNonNull(holding);
        double cost = cost(offer, emerald);
        if (offer.income()) {
            double gain = offer.result().count() * emerald;
            Goods sold = offer.first();
            boolean spare = holding.held().getOrDefault(sold.item(), 0) - wanted(holding, sold.item()) >= sold.count();
            String why = (spare ? "I can spare " : "I'd have to gather ") + words(sold) + " for " + emeraldWords(offer.result().count())
                    + (renewable(sold.item()) ? ", and they grow back" : "");
            // Selling what it can spare costs it only the gathering; what its run wants is not for sale.
            return new Deal(offer, true, spare ? cost : cost + gain, gain, why + ".");
        }
        double gain = gain(offer, holding);
        String what = what(offer);
        String price = offer.emeralds() > 0 ? emeraldWords(offer.emeralds()) : words(offer.first());
        String why = gain > cost
                ? what + " for " + price + ": worth it."
                : what + " for " + price + ": not worth it to me.";
        return new Deal(offer, false, cost, gain, why);
    }

    private static int wanted(Worth.Holding holding, String item) {
        return holding.wanted().getOrDefault(item, 0);
    }

    // ------------------------------------------------------------------ what to trade

    /** A trade to make, and how many times. */
    public record Step(Deal deal, int times) { }

    /**
     * What to trade at a villager: its purchases worth making, best for each emerald first, each once, and only those
     * it can pay for, with the emeralds it holds and what it can earn, and anything else the purchase asks for in hand (a
     * book for an enchanted book); and before them the income trades that pay for what its {@code emeralds} do not,
     * with goods it can spare, cheapest emerald first, and only while an emerald costs it less than what it buys is
     * worth for one.
     */
    public record Plan(List<Step> sell, List<Step> buy) {
        public boolean empty() { return sell.isEmpty() && buy.isEmpty(); }
    }

    public static Plan plan(List<Offer> offers, Worth.Holding holding, int emeralds) {
        double emerald = emeraldPrice(offers);
        var buys = new ArrayList<Deal>();
        var incomes = new ArrayList<Deal>();
        for (Offer offer : offers) {
            if (!offer.open()) continue;
            Deal deal = judge(offer, holding, emerald);
            if (deal.income()) incomes.add(deal);
            else if (deal.worthIt() && holds(holding, offer)) buys.add(deal);
        }
        buys.sort(Comparator.comparingDouble((Deal d) -> -d.profit() / Math.max(1, d.offer().emeralds())));
        incomes.sort(Comparator.comparingDouble((Deal d) -> d.cost() / d.offer().result().count()));
        // Each purchase best first, while what it holds and what it can earn for less than the purchase is worth pay for it.
        var buy = new ArrayList<Step>();
        int need = -emeralds;
        double leastPerEmerald = Double.MAX_VALUE;
        for (Deal deal : buys) {
            int price = deal.offer().emeralds();
            double perEmerald = price == 0 ? Double.MAX_VALUE : deal.gain() / price;
            double threshold = Math.min(leastPerEmerald, perEmerald);
            if (need + price > earnable(incomes, holding, threshold)) continue;
            buy.add(new Step(deal, 1));
            need += price;
            leastPerEmerald = threshold;
        }
        var sell = new ArrayList<Step>();
        for (Deal deal : incomes) {
            if (need <= 0) break;
            Offer offer = deal.offer();
            int each = offer.result().count();
            if (deal.cost() / each >= leastPerEmerald) break;
            int times = Math.min(sellable(offer, holding), (need + each - 1) / each);
            if (times <= 0) continue;
            sell.add(new Step(deal, times));
            need -= times * each;
        }
        return new Plan(List.copyOf(sell), List.copyOf(buy));
    }

    /** What it holds of everything a purchase asks for besides emeralds: a book for an enchanted book. */
    private static boolean holds(Worth.Holding holding, Offer offer) {
        for (Goods asked : new Goods[] {offer.first(), offer.second()}) {
            if (asked == null || asked.item().equals(EMERALD)) continue;
            if (holding.held().getOrDefault(asked.item(), 0) < asked.count()) return false;
        }
        return true;
    }

    /** How many times an income trade can be made with goods it can spare, before the villager restocks. */
    private static int sellable(Offer offer, Worth.Holding holding) {
        int spare = holding.held().getOrDefault(offer.first().item(), 0) - wanted(holding, offer.first().item());
        return Math.max(0, Math.min(offer.left(), spare / offer.first().count()));
    }

    /** Emeralds it can earn with what it can spare, from income cheaper an emerald than {@code under} minutes. */
    private static int earnable(List<Deal> incomes, Worth.Holding holding, double under) {
        int total = 0;
        for (Deal deal : incomes) {
            int each = deal.offer().result().count();
            if (deal.cost() / each < under) total += sellable(deal.offer(), holding) * each;
        }
        return total;
    }

    /** What a trade made {@code times} times was, in words: "sold 40 wheat for 2 emeralds", "bought a Mending book for 12 emeralds and a book". */
    public static String made(Offer offer, int times) {
        if (offer.income()) {
            return "sold " + words(new Goods(offer.first().item(), offer.first().count() * times)) + " for " + emeraldWords(offer.result().count() * times);
        }
        return "bought " + sold(offer, times);
    }

    /** What a villager sells by an offer and for what, in words: "a Mending book for 12 emeralds and a book". */
    public static String sells(Offer offer) {
        return sold(offer, 1);
    }

    private static String sold(Offer offer, int times) {
        String what = times == 1 ? lowerFirst(what(offer)) : words(new Goods(offer.result().item(), offer.result().count() * times));
        var paid = new ArrayList<String>();
        if (offer.emeralds() > 0) paid.add(emeraldWords(offer.emeralds() * times));
        for (Goods asked : new Goods[] {offer.first(), offer.second()}) {
            if (asked != null && !asked.item().equals(EMERALD)) paid.add(words(new Goods(asked.item(), asked.count() * times)));
        }
        return what + " for " + String.join(" and ", paid);
    }

    /** Its purchase worth most among {@code offers} as it stands, open or not; none if nothing is worth buying. */
    public static java.util.Optional<Deal> best(List<Offer> offers, Worth.Holding holding) {
        double emerald = emeraldPrice(offers);
        Deal best = null;
        for (Offer offer : offers) {
            if (offer.income()) continue;
            Deal deal = judge(offer, holding, emerald);
            if (deal.worthIt() && (best == null || deal.profit() > best.profit())) best = deal;
        }
        return java.util.Optional.ofNullable(best);
    }

    /** Trades made, in one sentence: "I sold 40 wheat for 2 emeralds and bought a Mending book for 12 emeralds and a book." */
    public static String told(List<String> made) {
        if (made.isEmpty()) return "";
        if (made.size() == 1) return "I " + made.getFirst() + ".";
        return "I " + String.join(", ", made.subList(0, made.size() - 1)) + " and " + made.getLast() + ".";
    }

    private static String lowerFirst(String text) {
        if (text.startsWith("A ") || text.startsWith("An ")) return Character.toLowerCase(text.charAt(0)) + text.substring(1);
        return text;
    }

    /**
     * Emeralds a day from a villager's income trades fed by what grows back: each made as often as it can be, its
     * villager restocking twice a day at its job block.
     */
    public static int renewableIncome(List<Offer> offers) {
        int total = 0;
        for (Offer offer : offers) if (offer.income() && renewable(offer.first().item())) total += offer.maxUses() * offer.result().count() * 2;
        return total;
    }

    /** An emerald a day of renewable income is worth this share of one mined, since what it buys is the gain. */
    static final double INCOME_SHARE = 0.1;

    /**
     * What a villager's offers are worth keeping it for, in minutes: the profit of each of its purchases worth making,
     * once (a second Mending book is worth less than the first), and its renewable income. A librarian with Mending is
     * worth a cell; a nitwit nothing.
     */
    public static double keepWorth(List<Offer> offers, Worth.Holding holding) {
        double emerald = emeraldPrice(offers);
        double total = 0;
        for (Offer offer : offers) {
            if (offer.income()) continue;
            Deal deal = judge(offer, holding, emerald);
            if (deal.worthIt()) total += deal.profit();
        }
        return total + renewableIncome(offers) * EMERALD_MINED * INCOME_SHARE;
    }

    // ------------------------------------------------------------------ what each profession buys and sells

    /** A trade a profession offers at a level (1 novice to 5 master), as vanilla's table has it: the usual price. */
    public record Typical(Profession profession, int level, Offer offer) { }

    private static final Map<Profession, List<Typical>> TABLE = new EnumMap<>(Profession.class);

    private static void buys(Profession profession, int level, String item, int count, int emeralds, int maxUses) {
        add(profession, level, new Offer(new Goods(item, count), new Goods(EMERALD, emeralds), maxUses));
    }

    private static void sells(Profession profession, int level, int emeralds, String item, int count, int maxUses, Enchant... enchants) {
        add(profession, level, new Offer(new Goods(EMERALD, emeralds), null, new Goods(item, count), List.of(enchants), 0, maxUses));
    }

    private static void add(Profession profession, int level, Offer offer) {
        TABLE.computeIfAbsent(profession, p -> new ArrayList<>()).add(new Typical(profession, level, offer));
    }

    static {
        var p = Profession.FARMER;
        buys(p, 1, "wheat", 20, 1, 16); buys(p, 1, "potato", 26, 1, 16); buys(p, 1, "carrot", 22, 1, 16); buys(p, 1, "beetroot", 15, 1, 16);
        sells(p, 1, 1, "bread", 6, 16); buys(p, 2, "pumpkin", 6, 1, 12); sells(p, 2, 1, "pumpkin_pie", 4, 12); sells(p, 2, 1, "apple", 4, 16);
        buys(p, 3, "melon", 4, 1, 12); sells(p, 3, 3, "cookie", 18, 12); sells(p, 5, 3, "golden_carrot", 3, 12);
        sells(p, 5, 4, "glistering_melon_slice", 3, 12);
        p = Profession.LIBRARIAN;
        buys(p, 1, "paper", 24, 1, 16); sells(p, 1, 9, "bookshelf", 1, 12); buys(p, 2, "book", 4, 1, 12); sells(p, 2, 1, "lantern", 1, 12);
        buys(p, 3, "ink_sac", 5, 1, 12); sells(p, 3, 1, "glass", 4, 12); buys(p, 4, "writable_book", 2, 1, 12);
        sells(p, 4, 5, "clock", 1, 12); sells(p, 4, 4, "compass", 1, 12); sells(p, 5, 20, "name_tag", 1, 12);
        // An enchanted book at every level up to expert: its enchantment the villager's own, its price by the level drawn.
        for (int level = 1; level <= 4; level++) {
            add(p, level, new Offer(new Goods(EMERALD, 20), new Goods("book", 1), new Goods("enchanted_book", 1), List.of(), 0, 12));
        }
        p = Profession.CLERIC;
        buys(p, 1, "rotten_flesh", 32, 1, 16); sells(p, 1, 1, "redstone", 2, 12); buys(p, 2, "gold_ingot", 3, 1, 12); sells(p, 2, 1, "lapis_lazuli", 1, 12);
        buys(p, 3, "rabbit_foot", 2, 1, 12); sells(p, 3, 4, "glowstone", 1, 12); buys(p, 4, "turtle_scute", 4, 1, 12);
        buys(p, 4, "glass_bottle", 9, 1, 12); sells(p, 4, 5, "ender_pearl", 1, 12); buys(p, 5, "nether_wart", 22, 1, 12);
        sells(p, 5, 3, "experience_bottle", 1, 12);
        p = Profession.ARMORER;
        buys(p, 1, "coal", 15, 1, 16); sells(p, 1, 7, "iron_leggings", 1, 12); sells(p, 1, 4, "iron_boots", 1, 12);
        sells(p, 1, 5, "iron_helmet", 1, 12); sells(p, 1, 9, "iron_chestplate", 1, 12); buys(p, 2, "iron_ingot", 4, 1, 12);
        sells(p, 2, 36, "bell", 1, 12); buys(p, 3, "lava_bucket", 1, 1, 12); buys(p, 3, "diamond", 1, 1, 12); sells(p, 3, 5, "shield", 1, 12);
        sells(p, 4, 19, "diamond_leggings", 1, 3, new Enchant("protection", 2)); sells(p, 4, 13, "diamond_boots", 1, 3, new Enchant("protection", 2));
        sells(p, 5, 13, "diamond_helmet", 1, 3, new Enchant("protection", 2)); sells(p, 5, 21, "diamond_chestplate", 1, 3, new Enchant("protection", 2));
        p = Profession.WEAPONSMITH;
        buys(p, 1, "coal", 15, 1, 16); sells(p, 1, 3, "iron_axe", 1, 12); sells(p, 1, 2, "iron_sword", 1, 3, new Enchant("sharpness", 2));
        buys(p, 2, "iron_ingot", 4, 1, 12); sells(p, 2, 36, "bell", 1, 12); buys(p, 3, "flint", 24, 1, 12);
        buys(p, 4, "diamond", 1, 1, 12); sells(p, 4, 12, "diamond_axe", 1, 3, new Enchant("sharpness", 2));
        sells(p, 5, 8, "diamond_sword", 1, 3, new Enchant("sharpness", 3));
        p = Profession.TOOLSMITH;
        buys(p, 1, "coal", 15, 1, 16); sells(p, 1, 1, "stone_pickaxe", 1, 12); buys(p, 2, "iron_ingot", 4, 1, 12); sells(p, 2, 36, "bell", 1, 12);
        buys(p, 3, "flint", 30, 1, 12); sells(p, 3, 3, "iron_pickaxe", 1, 3, new Enchant("efficiency", 2)); sells(p, 3, 4, "diamond_hoe", 1, 3);
        buys(p, 4, "diamond", 1, 1, 12); sells(p, 4, 12, "diamond_axe", 1, 3, new Enchant("efficiency", 2));
        sells(p, 4, 5, "diamond_shovel", 1, 3, new Enchant("efficiency", 2)); sells(p, 5, 13, "diamond_pickaxe", 1, 3, new Enchant("efficiency", 3));
        p = Profession.FLETCHER;
        buys(p, 1, "stick", 32, 1, 16); sells(p, 1, 1, "arrow", 16, 12); buys(p, 2, "flint", 26, 1, 12); sells(p, 2, 2, "bow", 1, 12);
        buys(p, 3, "string", 14, 1, 16); sells(p, 3, 3, "crossbow", 1, 12); buys(p, 4, "feather", 24, 1, 16);
        sells(p, 4, 2, "bow", 1, 3, new Enchant("power", 2)); buys(p, 5, "tripwire_hook", 8, 1, 12);
        p = Profession.BUTCHER;
        buys(p, 1, "chicken", 14, 1, 16); buys(p, 1, "porkchop", 7, 1, 16); buys(p, 1, "rabbit", 4, 1, 16); buys(p, 2, "coal", 15, 1, 16);
        sells(p, 2, 1, "cooked_porkchop", 5, 16); sells(p, 2, 1, "cooked_chicken", 8, 16); buys(p, 3, "mutton", 7, 1, 16);
        buys(p, 3, "beef", 10, 1, 16); buys(p, 4, "dried_kelp_block", 10, 1, 12); buys(p, 5, "sweet_berries", 10, 1, 12);
        p = Profession.FISHERMAN;
        buys(p, 1, "string", 20, 1, 16); buys(p, 1, "coal", 10, 1, 16); buys(p, 2, "cod", 15, 1, 16); buys(p, 3, "salmon", 13, 1, 16);
        buys(p, 4, "tropical_fish", 6, 1, 12); buys(p, 5, "pufferfish", 4, 1, 12);
        p = Profession.SHEPHERD;
        buys(p, 1, "white_wool", 18, 1, 16); sells(p, 1, 2, "shears", 1, 12); sells(p, 3, 3, "white_bed", 1, 12);
        p = Profession.LEATHERWORKER;
        buys(p, 1, "leather", 6, 1, 16); buys(p, 2, "flint", 26, 1, 12); buys(p, 3, "rabbit_hide", 9, 1, 12); sells(p, 5, 6, "saddle", 1, 12);
        p = Profession.MASON;
        buys(p, 1, "clay_ball", 10, 1, 16); buys(p, 2, "stone", 20, 1, 16); buys(p, 4, "quartz", 12, 1, 12);
        p = Profession.CARTOGRAPHER;
        buys(p, 1, "paper", 24, 1, 16); buys(p, 2, "glass_pane", 11, 1, 16); sells(p, 4, 7, "item_frame", 1, 12);
    }

    /** What a profession usually buys and sells, up to {@code level}: what a player looks for at one; nothing at a nitwit. */
    public static List<Typical> expected(Profession profession, int level) {
        var out = new ArrayList<Typical>();
        for (Typical each : TABLE.getOrDefault(profession, List.of())) if (each.level() <= level) out.add(each);
        return out;
    }

    /** The professions that usually sell {@code item}, and at what level, cheapest first: whom to look for. */
    public static List<Typical> sellers(String item) {
        var out = new ArrayList<Typical>();
        for (var list : TABLE.values()) for (Typical each : list) if (each.offer().result().item().equals(id(item))) out.add(each);
        out.sort(Comparator.comparingInt((Typical t) -> t.offer().emeralds()).thenComparingInt(Typical::level));
        return out;
    }

    // ------------------------------------------------------------------ words

    private static String what(Offer offer) {
        if (offer.result().item().equals(id("enchanted_book")) && !offer.enchants().isEmpty()) {
            var names = new ArrayList<String>();
            for (Enchant enchant : offer.enchants()) {
                Value value = ENCHANTS.get(enchant.id());
                String name = title(name(enchant.id()));
                names.add(value != null && value.max() == 1 ? name : name + " " + roman(enchant.level()));
            }
            String book = String.join(" and ", names) + " book";
            return (book.matches("^[AEIOU].*") ? "An " : "A ") + book;
        }
        return capital(words(offer.result()));
    }

    private static String title(String words) {
        var out = new StringBuilder();
        for (String word : words.split(" ")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(word.equals("of") || word.equals("the") ? word : capital(word));
        }
        return out.toString();
    }

    static String words(Goods goods) {
        String name = name(goods.item());
        if (goods.count() == 1) return (name.matches("^[aeiou].*") ? "an " : "a ") + name;
        return goods.count() + " " + plural(name);
    }

    private static String emeraldWords(int count) {
        return count == 1 ? "an emerald" : count + " emeralds";
    }

    private static String plural(String name) {
        if (name.endsWith("s") || name.endsWith("wheat") || name.endsWith("wool") || name.endsWith("flesh") || name.endsWith("paper")
                || name.endsWith("glass") || name.endsWith("redstone") || name.endsWith("lapis lazuli") || name.endsWith("glowstone")
                || name.endsWith("mutton") || name.endsWith("beef") || name.endsWith("cod") || name.endsWith("salmon") || name.endsWith("fish")
                || name.endsWith("bread") || name.endsWith("string") || name.endsWith("leather") || name.endsWith("coal") || name.endsWith("quartz")
                || name.endsWith("stone") || name.endsWith("wart") || name.endsWith("flint") || name.endsWith("gravel")) return name;
        if (name.endsWith("potato")) return name + "es";
        if (name.endsWith("y") && !name.endsWith("ey")) return name.substring(0, name.length() - 1) + "ies";
        if (name.endsWith("ch") || name.endsWith("sh")) return name + "es";
        return name + "s";
    }

    private static String name(String id) {
        return id.substring(id.indexOf(':') + 1).replace('_', ' ');
    }

    private static String capital(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            default -> String.valueOf(level);
        };
    }

    private static String id(String item) {
        return item.indexOf(':') >= 0 ? item : "minecraft:" + item;
    }
}
