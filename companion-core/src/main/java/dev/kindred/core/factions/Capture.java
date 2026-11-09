package dev.kindred.core.factions;

import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.Ledger;
import dev.kindred.core.record.WorldEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In plain terms: overclaim and capture, the rulebook's section 4. A faction whose claims exceed its power is
 * overextended, with as many chunks exposed as the difference. An enemy faction may then take one of its chunks, but only
 * one on its border (touching the wilderness or the attacker's own land) and inward from there, never a chunk deep
 * inside; only with the capacity and 100 coins for it; never within a new faction's first 48 hours (once a founder,
 * however often it founds again), never within 30 minutes of the faction's last loss, and never the same chunk twice in
 * an hour. Until a chunk is captured it is protected exactly as a secure one; once captured it is the new owner's, its
 * chests with it, nothing moved.
 *
 * Technical: Pure rules and the capture ledger (M-06e). A capture is ClaimLedger's {@code capture} event (the attacker
 * as {@code subject}, the loser as {@code data.from}) with the attacker's coins; the ledger keeps when each chunk was last
 * captured and when each faction last lost one.
 */
public final class Capture implements Ledger<Capture.Book> {
    public static final Capture INSTANCE = new Capture();
    public static final long HOUR = 3_600_000L;

    /** When each chunk was last captured, and when each faction last lost one. */
    public record Book(Map<String, Long> chunks, Map<String, Long> losses) {
        public static final Book EMPTY = new Book(Map.of(), Map.of());

        public Book {
            chunks = Collections.unmodifiableMap(new TreeMap<>(chunks));
            losses = Collections.unmodifiableMap(new TreeMap<>(losses));
        }
    }

    @Override public String name() { return "captures"; }

    @Override public Set<String> kinds() { return Set.of(ClaimLedger.CAPTURE); }

    @Override public Book empty() { return Book.EMPTY; }

    @Override
    public Book apply(Book book, WorldEvent event) {
        if (event.place().isEmpty()) return book;
        var chunks = new TreeMap<>(book.chunks());
        chunks.put(event.place(), event.realTimeMs());
        var losses = new TreeMap<>(book.losses());
        String from = event.get("from", "");
        if (!from.isEmpty()) losses.put(from, event.realTimeMs());
        return new Book(chunks, losses);
    }

    /** A faction's state of exposure: its claims, power and how many chunks are exposed. */
    public record Exposure(int claims, int power, int exposed) {
        public boolean overextended() { return exposed > 0; }
    }

    public static Exposure exposure(FactionBook factions, PowerBook powers, Map<String, String> claims, UUID faction, FactionSettings settings) {
        String id = faction.toString();
        int held = (int) claims.values().stream().filter(id::equals).count();
        int power = PowerRules.factionPower(factions, powers, faction, settings);
        return new Exposure(held, power, PowerRules.exposed(held, power));
    }

    /** Until when a new faction is shielded: 48 hours from its founder's first founding of any faction. */
    public static long newShieldUntil(FactionBook factions, Faction faction, FactionSettings settings) {
        long first = factions.founders().getOrDefault(faction.founder(), faction.foundedAtMs());
        return Math.min(first, faction.foundedAtMs()) + settings.newFactionShieldHours() * HOUR;
    }

    /** Until when a faction is shielded after its last loss. */
    public static long raidShieldUntil(Book captures, UUID faction, FactionSettings settings) {
        Long last = captures.losses().get(faction.toString());
        return last == null ? 0 : last + settings.raidShieldMinutes() * 60_000L;
    }

    /** {@code player} captures {@code chunk} for its faction, standing in it. */
    public static Territory.Decision capture(FactionBook factions, PowerBook powers, Map<String, String> claims, Set<String> safe, Book captures,
                                             long coins, FactionSettings settings, UUID player, Territory.Chunk chunk, Factions.At at) {
        var attacker = factions.of(player).orElse(null);
        if (attacker == null) return Territory.Decision.refused("You are not in a faction");
        if (!attacker.members().get(player).atLeast(Faction.Rank.OFFICER)) return Territory.Decision.refused("Only an officer or the leader captures land");
        if (safe.contains(chunk.key())) return Territory.Decision.refused("A safe zone is never captured");
        if (!Territory.playable(chunk, settings)) return Territory.Decision.refused("Beyond the border");
        String owner = claims.get(chunk.key());
        if (owner == null) return Territory.Decision.refused("Nobody's land: claim it instead");
        if (owner.equals(attacker.id().toString())) return Territory.Decision.refused("Already yours");
        UUID defenderId = UUID.fromString(owner);
        var defender = factions.get(defenderId).orElse(null);
        if (defender == null) return Territory.Decision.refused("No such faction");
        if (factions.relation(attacker.id(), defenderId) != Faction.Relation.ENEMY) return Territory.Decision.refused("Not an enemy faction");
        var exposure = exposure(factions, powers, claims, defenderId, settings);
        if (!exposure.overextended()) return Territory.Decision.refused(defender.name() + " is secure: claims " + exposure.claims() + ", power " + exposure.power());
        long now = at.realTimeMs();
        long shield = newShieldUntil(factions, defender, settings);
        if (now < shield) return Territory.Decision.refused(defender.name() + " is a new faction, shielded for " + minutes(shield - now) + " more minutes");
        long raid = raidShieldUntil(captures, defenderId, settings);
        if (now < raid) return Territory.Decision.refused(defender.name() + " lost land lately, shielded for " + minutes(raid - now) + " more minutes");
        Long last = captures.chunks().get(chunk.key());
        if (last != null && now - last < HOUR) return Territory.Decision.refused("This chunk changed hands within the hour");
        if (!onTheBorder(claims, safe, owner, attacker.id().toString(), chunk)) {
            return Territory.Decision.refused("Not on the border: take a chunk touching the wilderness or your own land first");
        }
        String mine = attacker.id().toString();
        long held = claims.values().stream().filter(mine::equals).count();
        int power = PowerRules.factionPower(factions, powers, attacker.id(), settings);
        if (held + 1 > PowerRules.capacity(power, settings)) {
            return Territory.Decision.refused("Over capacity: " + held + " claimed of " + PowerRules.capacity(power, settings) + " (power " + power + ")");
        }
        if (coins < settings.claimCost() * Coins.CENT) return Territory.Decision.refused("A capture costs " + settings.claimCost() + " coins; you have " + Coins.show(coins));
        var data = new LinkedHashMap<String, String>();
        data.put("from", owner);
        data.put("by", player.toString());
        var taken = WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), ClaimLedger.CAPTURE, player, mine, chunk.key(), data);
        var cost = Coins.change(new Coins.Book(Map.of(player, coins), 0), player, -settings.claimCost() * Coins.CENT, "capture " + chunk.key(), at, chunk.key()).orElseThrow();
        return new Territory.Decision(List.of(taken, cost), "Captured " + chunk.key() + " from " + defender.name());
    }

    /**
     * Whether {@code chunk} of {@code owner}'s is on its border for {@code attacker}: an edge touching the wilderness (no
     * claim, no safe zone) or the attacker's own land. Another faction's land is no way in.
     */
    public static boolean onTheBorder(Map<String, String> claims, Set<String> safe, String owner, String attacker, Territory.Chunk chunk) {
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            var next = chunk.offset(d[0], d[1]);
            String held = claims.get(next.key());
            if (held == null && !safe.contains(next.key())) return true;
            if (attacker.equals(held)) return true;
        }
        return false;
    }

    /** The announcement events a change of exposure makes: vulnerable when a faction first overextends, secure when it no longer does. */
    public static WorldEvent notice(UUID faction, boolean vulnerable, Exposure exposure, Factions.At at) {
        var data = Map.of("claims", String.valueOf(exposure.claims()), "power", String.valueOf(exposure.power()), "exposed", String.valueOf(exposure.exposed()));
        return WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), vulnerable ? "faction.vulnerable" : "faction.secure", null, faction.toString(), "", data);
    }

    private static long minutes(long ms) { return (ms + 59_999) / 60_000; }
}
