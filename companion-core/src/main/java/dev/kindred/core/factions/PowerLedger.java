package dev.kindred.core.factions;

import dev.kindred.core.record.Ledger;
import dev.kindred.core.record.WorldEvent;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: each player's power worked out from the World Record: online time that earns it back a point an hour
 * (kept to the second across logouts and restarts), the PvP deaths that cost it, and an operator's corrections.
 *
 * Technical: The power ledger (M-06c). Every power event carries the player's power and progress after it
 * ({@code data.power}, {@code data.progress}), worked out by {@link PowerRules} under the settings of its day, so a
 * replay never depends on today's settings. {@code power.online} also carries the seconds it adds, {@code power.loss}
 * its amount and killer (stamping the pair for the anti-farming cooldown), {@code power.set} is an operator's
 * correction, and {@code account.seen} marks an account's first sight.
 */
public final class PowerLedger implements Ledger<PowerBook> {
    public static final String ONLINE = "power.online", LOSS = "power.loss", SET = "power.set", SEEN = "account.seen";
    public static final PowerLedger INSTANCE = new PowerLedger();

    @Override public String name() { return "power"; }

    @Override public Set<String> kinds() { return Set.of(ONLINE, LOSS, SET, SEEN); }

    @Override public PowerBook empty() { return PowerBook.EMPTY; }

    @Override
    public PowerBook apply(PowerBook book, WorldEvent event) {
        UUID player = event.actor();
        if (player == null) return book;
        try {
            return switch (event.kind()) {
                case ONLINE, SET -> book.with(player, after(event));
                case LOSS -> {
                    var next = book.with(player, after(event));
                    String killer = event.get("killer", "");
                    yield killer.isEmpty() ? next : next.counted(PowerBook.pair(UUID.fromString(killer), player), event.realTimeMs());
                }
                case SEEN -> book.seen(player, event.realTimeMs());
                default -> book;
            };
        } catch (IllegalArgumentException malformed) {
            return book;
        }
    }

    private static PowerBook.Power after(WorldEvent event) {
        return new PowerBook.Power(Integer.parseInt(event.get("power", "")), Integer.parseInt(event.get("progress", "")));
    }
}
