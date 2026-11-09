package dev.kindred.core.factions;

import dev.kindred.core.record.Ledger;
import dev.kindred.core.record.WorldEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: each player's coins, from the World Record: the 500 every account starts with in a faction world
 * (the same rule for people and AI players), what claims cost, and later what sales pay and purchases cost.
 *
 * Technical: The coin ledger (M-06d; the market's own movements are M-06g's). Amounts are in hundredths of a coin
 * ({@link #CENT}), so the market's floors can be positive below a coin. Every {@code coins} event names the player as its
 * actor and carries {@code amount} (positive or negative), {@code balance} after it and {@code for} (what it was for);
 * the last balance is the balance. A balance is never negative: the rules refuse what it cannot pay.
 * {@code minted} counts every positive amount whose {@code for} is {@code start}: coins come into the world only so.
 */
public final class Coins implements Ledger<Coins.Book> {
    public static final String KIND = "coins";
    public static final String START = "start";
    /** Hundredths of a coin to the coin. */
    public static final long CENT = 100;

    /** A balance in hundredths as people read it: {@code 12.05}. */
    public static String show(long cents) {
        return (cents < 0 ? "-" : "") + Math.abs(cents) / CENT + "." + String.format(java.util.Locale.ROOT, "%02d", Math.abs(cents) % CENT);
    }
    public static final Coins INSTANCE = new Coins();

    /** Balances, and the coins the start rule put into the world. */
    public record Book(Map<UUID, Long> balances, long minted) {
        public static final Book EMPTY = new Book(Map.of(), 0);

        public Book { balances = Collections.unmodifiableMap(new LinkedHashMap<>(balances)); }

        public long of(UUID player) { return balances.getOrDefault(player, 0L); }

        public boolean started(UUID player) { return balances.containsKey(player); }
    }

    @Override public String name() { return "coins"; }

    @Override public Set<String> kinds() { return Set.of(KIND); }

    @Override public Book empty() { return Book.EMPTY; }

    @Override
    public Book apply(Book book, WorldEvent event) {
        if (event.actor() == null) return book;
        try {
            long balance = Long.parseLong(event.get("balance", ""));
            long amount = Long.parseLong(event.get("amount", "0"));
            var next = new LinkedHashMap<>(book.balances());
            next.put(event.actor(), balance);
            return new Book(next, book.minted() + (START.equals(event.get("for", "")) && amount > 0 ? amount : 0));
        } catch (NumberFormatException malformed) {
            return book;
        }
    }

    /** A change of {@code amount} to {@code player}'s balance, for {@code reason}; refused (empty) if it would go negative. */
    public static java.util.Optional<WorldEvent> change(Book book, UUID player, long amount, String reason, Factions.At at, String place) {
        long after = book.of(player) + amount;
        if (after < 0) return java.util.Optional.empty();
        var data = new LinkedHashMap<String, String>();
        data.put("amount", String.valueOf(amount));
        data.put("balance", String.valueOf(after));
        data.put("for", reason);
        return java.util.Optional.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), KIND, player, "", place, data));
    }

    /** The starting coins, once an account, when it is first seen in a faction world. */
    public static java.util.Optional<WorldEvent> start(Book book, UUID player, FactionSettings settings, Factions.At at) {
        if (book.started(player)) return java.util.Optional.empty();
        return change(book, player, settings.startCoins() * CENT, START, at, "");
    }
}
