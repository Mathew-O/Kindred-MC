package dev.kindred.core.factions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: one faction: its name and tag, its colour, its members and their ranks, what it wants to be to each
 * other faction, and its home.
 *
 * Technical: A faction as the faction ledger holds it (M-06c). {@code wishes} is what this faction has declared toward
 * another; the relation in force is {@link FactionBook#relation}, which needs both sides for an alliance or a truce.
 * Exactly one member is the leader. People and AI players are members alike.
 */
public record Faction(UUID id, String name, String tag, FactionColour colour, Map<UUID, Rank> members, Map<UUID, Relation> wishes,
                      String home, long foundedAtMs, UUID founder) {
    /** A member's rank. */
    public enum Rank { LEADER, OFFICER, MEMBER;
        public boolean atLeast(Rank other) { return ordinal() <= other.ordinal(); }
    }

    /** What two factions are to each other. Enemy unless both agree to more. */
    public enum Relation { ENEMY, TRUCE, ALLY }

    public Faction {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(tag);
        Objects.requireNonNull(colour);
        Objects.requireNonNull(home);
        Objects.requireNonNull(founder);
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        wishes = Collections.unmodifiableMap(new LinkedHashMap<>(wishes));
        if (members.values().stream().filter(rank -> rank == Rank.LEADER).count() != 1) throw new IllegalArgumentException("A faction has one leader");
    }

    public UUID leader() {
        return members.entrySet().stream().filter(e -> e.getValue() == Rank.LEADER).map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    public Optional<Rank> rank(UUID player) { return Optional.ofNullable(members.get(player)); }

    Faction withMembers(Map<UUID, Rank> next) { return new Faction(id, name, tag, colour, next, wishes, home, foundedAtMs, founder); }
    Faction withWishes(Map<UUID, Relation> next) { return new Faction(id, name, tag, colour, members, next, home, foundedAtMs, founder); }
    Faction withHome(String next) { return new Faction(id, name, tag, colour, members, wishes, next, foundedAtMs, founder); }
    Faction withColour(FactionColour next) { return new Faction(id, name, tag, next, members, wishes, home, foundedAtMs, founder); }
}
