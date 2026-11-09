package dev.kindred.core.market;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In plain terms: the market's starting prices: what each raw material is worth to begin with, how fast its price moves,
 * whether the market sells it as well as buys it, what other items count as (a block of iron is nine ingots), what an
 * enchantment adds, and which items are never paid for.
 *
 * Technical: The baseline table (M-06g), read from {@code market.txt} beside this class. Prices are in hundredths of a
 * coin. Lines: {@code root <item> <cents> <mass|ordinary|scarce> [nobuy]}, {@code as <item> <unit> <ratio or fraction>},
 * {@code enchant <id> <cents a level>}, {@code admin <item>} (never paid), {@code container <item>}. Items reached
 * by a recipe from priced items are priced from it ({@link Pricing}); anything else is valuation pending.
 */
public record MarketTable(Map<String, Root> roots, Map<String, As> as, Map<String, Long> enchantments, Set<String> admin, Set<String> containers) {
    /** How fast a material's price moves with its stock, how far, and how soon it comes back. */
    public enum Volatility {
        /** Mass-farmable: moves slowly, a low floor. */
        MASS(0.0004, 0.10, 2.0, 12),
        ORDINARY(0.002, 0.20, 3.0, 6),
        /** Scarce: moves more, but within its ceiling. */
        SCARCE(0.02, 0.30, 4.0, 3);

        /** The price falls by this share of itself for each unit sold over equilibrium. */
        public final double sensitivity;
        /** Floor and ceiling as shares of the baseline. */
        public final double floor, ceiling;
        /** Hours for an untraded stock to come halfway back to equilibrium. */
        public final double halfLifeHours;

        Volatility(double sensitivity, double floor, double ceiling, double halfLifeHours) {
            this.sensitivity = sensitivity;
            this.floor = floor;
            this.ceiling = ceiling;
            this.halfLifeHours = halfLifeHours;
        }
    }

    /** A raw material with a price of its own. */
    public record Root(String item, long baseline, Volatility volatility, boolean buyable) {
        public Root {
            Objects.requireNonNull(item);
            Objects.requireNonNull(volatility);
            if (baseline < 1) throw new IllegalArgumentException("A baseline is at least a hundredth of a coin");
        }
    }

    /** An item that counts as {@code ratio} of {@code unit} (a root). */
    public record As(String unit, double ratio) {
        public As { if (ratio <= 0) throw new IllegalArgumentException("A ratio is positive"); }
    }

    public MarketTable {
        roots = Collections.unmodifiableMap(new LinkedHashMap<>(roots));
        as = Collections.unmodifiableMap(new LinkedHashMap<>(as));
        enchantments = Collections.unmodifiableMap(new LinkedHashMap<>(enchantments));
        admin = Set.copyOf(admin);
        containers = Set.copyOf(containers);
        for (var entry : as.entrySet()) if (!roots.containsKey(entry.getValue().unit())) throw new IllegalArgumentException(entry.getKey() + " counts as an unknown unit");
    }

    /** The table bundled with Kindred. */
    public static MarketTable bundled() {
        try (InputStream in = MarketTable.class.getResourceAsStream("market.txt")) {
            if (in == null) throw new IllegalStateException("Missing market table");
            return parse(in);
        } catch (IOException failure) {
            throw new IllegalStateException("Market table unreadable", failure);
        }
    }

    public static MarketTable parse(InputStream in) throws IOException {
        var roots = new LinkedHashMap<String, Root>();
        var as = new LinkedHashMap<String, As>();
        var enchant = new LinkedHashMap<String, Long>();
        var admin = new java.util.HashSet<String>();
        var containers = new java.util.HashSet<String>();
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        int number = 0;
        for (String line; (line = reader.readLine()) != null; ) {
            number++;
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] w = line.split("\\s+");
            try {
                switch (w[0]) {
                    case "root" -> roots.put(id(w[1]), new Root(id(w[1]), Long.parseLong(w[2]), Volatility.valueOf(w[3].toUpperCase(java.util.Locale.ROOT)),
                            !(w.length > 4 && w[4].equals("nobuy"))));
                    case "as" -> as.put(id(w[1]), new As(id(w[2]), ratio(w[3])));
                    case "enchant" -> enchant.put(id(w[1]), Long.parseLong(w[2]));
                    case "admin" -> admin.add(id(w[1]));
                    case "container" -> containers.add(id(w[1]));
                    default -> throw new IllegalArgumentException("Unknown entry " + w[0]);
                }
            } catch (RuntimeException bad) {
                throw new IllegalArgumentException("market.txt line " + number + ": " + line, bad);
            }
        }
        return new MarketTable(roots, as, enchant, admin, containers);
    }

    /** A ratio as written: a number, or an exact fraction such as {@code 1/9}, so nine nuggets make exactly an ingot. */
    static double ratio(String text) {
        int slash = text.indexOf('/');
        return slash < 0 ? Double.parseDouble(text) : Double.parseDouble(text.substring(0, slash)) / Double.parseDouble(text.substring(slash + 1));
    }

    public static String id(String item) { return item.indexOf(':') >= 0 ? item : "minecraft:" + item; }

    /** The same table with {@code root} set, as an operator sets a baseline. */
    public MarketTable with(Root root) {
        var next = new LinkedHashMap<>(roots);
        next.put(root.item(), root);
        var nextAs = new LinkedHashMap<>(as);
        nextAs.remove(root.item());
        return new MarketTable(next, nextAs, enchantments, admin, containers);
    }
}
