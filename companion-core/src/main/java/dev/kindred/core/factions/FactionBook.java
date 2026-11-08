package dev.kindred.core.factions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: every faction on the server, who is in which, who has been invited where, and who has founded a
 * faction before: the faction ledger.
 *
 * Technical: The state of {@link FactionLedger} (M-06c), rebuilt from the World Record. {@code founders} keeps every
 * player who ever founded a faction, with when, so the new-faction shield (M-06e) is once a player, not reset by
 * founding again.
 */
public record FactionBook(Map<UUID, Faction> factions, Map<UUID, Set<UUID>> invites, Map<UUID, Long> founders) {
    public static final FactionBook EMPTY = new FactionBook(Map.of(), Map.of(), Map.of());

    public FactionBook {
        factions = Collections.unmodifiableMap(new LinkedHashMap<>(factions));
        var copy = new LinkedHashMap<UUID, Set<UUID>>();
        invites.forEach((faction, players) -> copy.put(faction, Set.copyOf(players)));
        invites = Collections.unmodifiableMap(copy);
        founders = Collections.unmodifiableMap(new LinkedHashMap<>(founders));
    }

    /** The faction {@code player} is in. */
    public Optional<Faction> of(UUID player) {
        if (player == null) return Optional.empty();
        return factions.values().stream().filter(f -> f.members().containsKey(player)).findFirst();
    }

    public Optional<Faction> get(UUID faction) { return Optional.ofNullable(factions.get(faction)); }

    /** A faction by its name or tag, ignoring case. */
    public Optional<Faction> named(String text) {
        if (text == null) return Optional.empty();
        String wanted = text.trim();
        return factions.values().stream().filter(f -> f.name().equalsIgnoreCase(wanted) || f.tag().equalsIgnoreCase(wanted)).findFirst();
    }

    /** The relation in force: an alliance needs both to wish it, a truce both to wish at least a truce; otherwise enemies. */
    public Faction.Relation relation(UUID a, UUID b) {
        if (a == null || b == null) return Faction.Relation.ENEMY;
        if (a.equals(b)) return Faction.Relation.ALLY;
        Faction one = factions.get(a), two = factions.get(b);
        if (one == null || two == null) return Faction.Relation.ENEMY;
        var x = one.wishes().getOrDefault(b, Faction.Relation.ENEMY);
        var y = two.wishes().getOrDefault(a, Faction.Relation.ENEMY);
        if (x == Faction.Relation.ALLY && y == Faction.Relation.ALLY) return Faction.Relation.ALLY;
        if (x != Faction.Relation.ENEMY && y != Faction.Relation.ENEMY) return Faction.Relation.TRUCE;
        return Faction.Relation.ENEMY;
    }

    public boolean invited(UUID faction, UUID player) { return invites.getOrDefault(faction, Set.of()).contains(player); }

    FactionBook put(Faction faction) {
        var next = new LinkedHashMap<>(factions);
        next.put(faction.id(), faction);
        return new FactionBook(next, invites, founders);
    }

    FactionBook remove(UUID faction) {
        var next = new LinkedHashMap<>(factions);
        next.remove(faction);
        var nextInvites = new LinkedHashMap<>(invites);
        nextInvites.remove(faction);
        // Its wishes toward others go with it, and others' wishes toward it.
        for (var entry : next.entrySet()) {
            if (entry.getValue().wishes().containsKey(faction)) {
                var wishes = new LinkedHashMap<>(entry.getValue().wishes());
                wishes.remove(faction);
                entry.setValue(entry.getValue().withWishes(wishes));
            }
        }
        return new FactionBook(next, nextInvites, founders);
    }

    FactionBook invite(UUID faction, UUID player, boolean add) {
        var nextInvites = new LinkedHashMap<>(invites);
        var players = new LinkedHashSet<>(nextInvites.getOrDefault(faction, Set.of()));
        if (add) players.add(player); else players.remove(player);
        if (players.isEmpty()) nextInvites.remove(faction); else nextInvites.put(faction, players);
        return new FactionBook(factions, nextInvites, founders);
    }

    FactionBook founded(UUID player, long atMs) {
        if (founders.containsKey(player)) return this;
        var next = new LinkedHashMap<>(founders);
        next.put(player, atMs);
        return new FactionBook(factions, invites, next);
    }
}
