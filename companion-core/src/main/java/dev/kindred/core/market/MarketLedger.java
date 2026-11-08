package dev.kindred.core.market;

import dev.kindred.core.record.Ledger;
import dev.kindred.core.record.WorldEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * In plain terms: the market's prices worked out from the World Record: every sale and purchase, with where each raw
 * material's stock stood after it, and every baseline an operator set.
 *
 * Technical: The market ledger (M-06g). {@code market.sale} and {@code market.buy} carry the states of the roots they
 * moved, after them, in {@code data.roots} ({@code root=stock;updatedMs;anchor;anchorMs|...}); the fold sets them, so a
 * replay never recomputes a curve. {@code market.baseline} carries an operator's root ({@code cents},
 * {@code volatility}, {@code buyable}). {@code market.signal} is a counted auction trade's demand (M-06h), its roots' states after it.
 */
public final class MarketLedger implements Ledger<MarketBook> {
    public static final String SALE = "market.sale", BUY = "market.buy", BASELINE = "market.baseline", SIGNAL = "market.signal";
    public static final MarketLedger INSTANCE = new MarketLedger();

    @Override public String name() { return "market"; }

    @Override public Set<String> kinds() { return Set.of(SALE, BUY, BASELINE, SIGNAL); }

    @Override public MarketBook empty() { return MarketBook.EMPTY; }

    @Override
    public MarketBook apply(MarketBook book, WorldEvent event) {
        try {
            if (event.kind().equals(BASELINE)) {
                return book.withBaseline(new MarketTable.Root(event.subject(), Long.parseLong(event.get("cents", "")),
                        MarketTable.Volatility.valueOf(event.get("volatility", "")), Boolean.parseBoolean(event.get("buyable", "true"))));
            }
            return book.with(decode(event.get("roots", "")));
        } catch (IllegalArgumentException malformed) {
            return book;
        }
    }

    public static String encode(Map<String, MarketBook.State> states) {
        var out = new StringBuilder();
        for (var e : states.entrySet()) {
            if (!out.isEmpty()) out.append('|');
            var s = e.getValue();
            out.append(e.getKey()).append('=').append(s.stock()).append(';').append(s.updatedMs()).append(';').append(s.anchor()).append(';').append(s.anchorMs());
        }
        return out.toString();
    }

    public static Map<String, MarketBook.State> decode(String text) {
        var out = new LinkedHashMap<String, MarketBook.State>();
        if (text.isEmpty()) return out;
        for (String part : text.split("\\|")) {
            int eq = part.lastIndexOf('=');
            String[] v = part.substring(eq + 1).split(";");
            out.put(part.substring(0, eq), new MarketBook.State(Double.parseDouble(v[0]), Long.parseLong(v[1]), Double.parseDouble(v[2]), Long.parseLong(v[3])));
        }
        return out;
    }
}
