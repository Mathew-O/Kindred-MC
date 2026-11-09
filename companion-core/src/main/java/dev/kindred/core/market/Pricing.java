package dev.kindred.core.market;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In plain terms: what the market pays and asks for anything right now. A raw material has a price of its own that
 * falls as the market fills with it and comes back as time passes; anything made has the price of what it is made of by
 * its cheapest recipe; and nothing is ever paid more than it would cost to make from what the market sells, so no
 * buying, crafting, smelting or uncrafting can make coins out of nothing.
 *
 * Technical: The price engine (M-06g), pure over the table, the recipes and the market book. An item's mid price is
 * the lesser of its own (a root's curve, or an {@code as} item's ratio of its unit) and every recipe's cost from its
 * ingredients' mid prices, the cheapest alternative of each; the path it takes carries the roots a sale moves. Sales pay
 * the mid less {@link #SPREAD}, purchases (of a buyable root or an {@code as} item of one) ask its own mid plus it.
 * Since a sale pays at most the made cost and a purchase asks at least its own price, a loop always loses the spread.
 */
public final class Pricing {
    /** Each side's spread off the mid price. */
    public static final double SPREAD = 0.15;
    /** The most a mid price moves from where it stood an hour before. */
    public static final double MAX_HOURLY = 0.20;
    static final long HOUR_MS = 3_600_000;

    private final MarketTable table;
    private final Map<String, List<Recipe>> recipesFor = new HashMap<>();

    public Pricing(MarketTable table, List<Recipe> recipes) {
        this.table = table;
        for (Recipe r : recipes) recipesFor.computeIfAbsent(MarketTable.id(r.output()), k -> new ArrayList<>()).add(r);
    }

    public MarketTable table() { return table; }

    /** A price, in hundredths of a coin, and the roots (with their shares) a unit of it stands for. */
    public record Path(double mid, Map<String, Double> roots) {}

    /** The root an item names, with the operator's baseline in force where it set one. */
    public Optional<MarketTable.Root> root(MarketBook book, String item) {
        var set = book.baselines().get(item);
        if (set != null) return Optional.of(set);
        return Optional.ofNullable(table.roots().get(item));
    }

    /** A root's mid price at {@code nowMs}: its curve at its decayed stock, within its floor and ceiling and the hour's limit. */
    public double rootMid(MarketBook book, MarketTable.Root root, long nowMs) {
        return mid(root, book.state(root.item()), nowMs);
    }

    static double decayed(MarketTable.Root root, MarketBook.State state, long nowMs) {
        if (state.updatedMs() == 0 || state.stock() == 0) return state.stock();
        double hours = Math.max(0, nowMs - state.updatedMs()) / (double) HOUR_MS;
        return state.stock() * Math.pow(0.5, hours / root.volatility().halfLifeHours);
    }

    static double mid(MarketTable.Root root, MarketBook.State state, long nowMs) {
        var v = root.volatility();
        double raw = root.baseline() * Math.exp(-v.sensitivity * decayed(root, state, nowMs));
        double price = Math.max(root.baseline() * v.floor, Math.min(root.baseline() * v.ceiling, raw));
        if (state.anchor() > 0 && nowMs - state.anchorMs() < HOUR_MS) {
            price = Math.max(state.anchor() * (1 - MAX_HOURLY), Math.min(state.anchor() * (1 + MAX_HOURLY), price));
        }
        return price;
    }

    /** An item's own price (as a root or a share of one), without its recipes; for purchases. */
    public Optional<Path> own(MarketBook book, String item, long nowMs) {
        var root = root(book, item);
        if (root.isPresent()) return Optional.of(new Path(rootMid(book, root.get(), nowMs), Map.of(item, 1.0)));
        var as = table.as().get(item);
        if (as == null) return Optional.empty();
        var unit = root(book, as.unit()).orElseThrow();
        return Optional.of(new Path(as.ratio() * rootMid(book, unit, nowMs), Map.of(as.unit(), as.ratio())));
    }

    /** An item's mid price now: the least of its own and its recipes' costs; empty when nothing prices it (pending). */
    public Optional<Path> price(MarketBook book, String item, long nowMs) {
        return price(book, MarketTable.id(item), nowMs, new HashMap<>(), new HashSet<>());
    }

    private Optional<Path> price(MarketBook book, String item, long nowMs, Map<String, Optional<Path>> memo, Set<String> making) {
        var known = memo.get(item);
        if (known != null) return known;
        if (!making.add(item)) return Optional.empty();
        Path best = own(book, item, nowMs).orElse(null);
        for (Recipe recipe : recipesFor.getOrDefault(item, List.of())) {
            double cost = 0;
            var roots = new LinkedHashMap<String, Double>();
            boolean priced = true;
            for (Set<String> ingredient : recipe.ingredients()) {
                Path cheapest = null;
                for (String option : ingredient) {
                    var p = price(book, MarketTable.id(option), nowMs, memo, making);
                    if (p.isPresent() && (cheapest == null || p.get().mid() < cheapest.mid())) cheapest = p.get();
                }
                if (cheapest == null) { priced = false; break; }
                cost += cheapest.mid();
                cheapest.roots().forEach((r, share) -> roots.merge(r, share, Double::sum));
            }
            if (!priced) continue;
            double perUnit = cost / recipe.count();
            if (best == null || perUnit < best.mid()) {
                var shares = new LinkedHashMap<String, Double>();
                roots.forEach((r, share) -> shares.put(r, share / recipe.count()));
                best = new Path(perUnit, shares);
            }
        }
        making.remove(item);
        var result = Optional.ofNullable(best);
        memo.put(item, result);
        return result;
    }

    /** What enchantments add to an item, in hundredths, at their table's value a level. */
    public long enchantments(Map<String, Integer> levels) {
        long total = 0;
        for (var e : levels.entrySet()) total += table.enchantments().getOrDefault(MarketTable.id(e.getKey()), 0L) * e.getValue();
        return total;
    }

    /** What the market pays for one at {@code mid}, rounded down. */
    public static long sellPrice(double mid) { return (long) Math.floor(mid * (1 - SPREAD)); }

    /** What the market asks for one at {@code mid}, rounded up. */
    public static long buyPrice(double mid) { return (long) Math.ceil(mid * (1 + SPREAD)); }

    /** {@code roots} moved by {@code units} each (positive when the market takes them in), as states at {@code nowMs}. */
    public Map<String, MarketBook.State> move(MarketBook book, Map<String, Double> roots, double units, long nowMs) {
        var out = new LinkedHashMap<String, MarketBook.State>();
        for (var entry : roots.entrySet()) {
            var root = root(book, entry.getKey()).orElseThrow();
            var state = book.state(root.item());
            double before = mid(root, state, nowMs);
            double anchor = state.anchor();
            long anchorMs = state.anchorMs();
            if (anchor <= 0 || nowMs - anchorMs >= HOUR_MS) {
                anchor = before;
                anchorMs = nowMs;
            }
            out.put(root.item(), new MarketBook.State(decayed(root, state, nowMs) + entry.getValue() * units, nowMs, anchor, anchorMs));
        }
        return out;
    }
}
