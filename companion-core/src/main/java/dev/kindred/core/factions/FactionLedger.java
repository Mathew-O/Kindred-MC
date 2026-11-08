package dev.kindred.core.factions;

import dev.kindred.core.record.Ledger;
import dev.kindred.core.record.WorldEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: the factions worked out from the World Record: each founding, invitation, joining, leaving, rank,
 * relation, home, colour and disbanding, in order.
 *
 * Technical: The faction ledger (M-06c). Every event names the faction as {@code subject}; {@link Factions} writes only
 * events its rules accepted, so the fold does not judge them, but it ignores any it cannot apply (a join to a faction
 * that no longer exists) rather than fail a replay.
 */
public final class FactionLedger implements Ledger<FactionBook> {
    public static final String CREATE = "faction.create", INVITE = "faction.invite", JOIN = "faction.join", LEAVE = "faction.leave",
            RANK = "faction.rank", WISH = "faction.wish", HOME = "faction.home", COLOUR = "faction.colour", DISBAND = "faction.disband";
    public static final FactionLedger INSTANCE = new FactionLedger();

    @Override public String name() { return "factions"; }

    @Override public Set<String> kinds() { return Set.of(CREATE, INVITE, JOIN, LEAVE, RANK, WISH, HOME, COLOUR, DISBAND); }

    @Override public FactionBook empty() { return FactionBook.EMPTY; }

    @Override
    public FactionBook apply(FactionBook book, WorldEvent event) {
        UUID id;
        try {
            id = UUID.fromString(event.subject());
        } catch (IllegalArgumentException malformed) {
            return book;
        }
        Faction faction = book.factions().get(id);
        try {
            switch (event.kind()) {
                case CREATE -> {
                    if (faction != null || event.actor() == null || book.of(event.actor()).isPresent()) return book;
                    var colour = FactionColour.parse(event.get("colour", "")).orElse(FactionColour.WHITE);
                    var created = new Faction(id, event.get("name", ""), event.get("tag", ""), colour, Map.of(event.actor(), Faction.Rank.LEADER),
                            Map.of(), "", event.realTimeMs(), event.actor());
                    return book.put(created).founded(event.actor(), event.realTimeMs());
                }
                case INVITE -> {
                    if (faction == null) return book;
                    return book.invite(id, UUID.fromString(event.get("player", "")), !"0".equals(event.get("add", "1")));
                }
                case JOIN -> {
                    if (faction == null || event.actor() == null || book.of(event.actor()).isPresent()) return book;
                    var members = new LinkedHashMap<>(faction.members());
                    members.put(event.actor(), Faction.Rank.MEMBER);
                    return book.put(faction.withMembers(members)).invite(id, event.actor(), false);
                }
                case LEAVE -> {
                    if (faction == null || event.actor() == null || !faction.members().containsKey(event.actor())
                            || faction.members().get(event.actor()) == Faction.Rank.LEADER) return book;
                    var members = new LinkedHashMap<>(faction.members());
                    members.remove(event.actor());
                    return book.put(faction.withMembers(members));
                }
                case RANK -> {
                    if (faction == null) return book;
                    UUID player = UUID.fromString(event.get("player", ""));
                    var rank = Faction.Rank.valueOf(event.get("rank", ""));
                    if (!faction.members().containsKey(player)) return book;
                    var members = new LinkedHashMap<>(faction.members());
                    if (rank == Faction.Rank.LEADER) members.put(faction.leader(), Faction.Rank.OFFICER);
                    else if (faction.leader().equals(player)) return book;
                    members.put(player, rank);
                    return book.put(faction.withMembers(members));
                }
                case WISH -> {
                    if (faction == null) return book;
                    UUID other = UUID.fromString(event.get("other", ""));
                    var wishes = new LinkedHashMap<>(faction.wishes());
                    var relation = Faction.Relation.valueOf(event.get("relation", ""));
                    if (relation == Faction.Relation.ENEMY) wishes.remove(other); else wishes.put(other, relation);
                    return book.put(faction.withWishes(wishes));
                }
                case HOME -> { return faction == null ? book : book.put(faction.withHome(event.place())); }
                case COLOUR -> {
                    if (faction == null) return book;
                    return FactionColour.parse(event.get("colour", "")).map(c -> book.put(faction.withColour(c))).orElse(book);
                }
                case DISBAND -> { return faction == null ? book : book.remove(id); }
                default -> { return book; }
            }
        } catch (IllegalArgumentException malformed) {
            return book;
        }
    }
}
