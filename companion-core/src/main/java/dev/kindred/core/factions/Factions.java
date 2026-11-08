package dev.kindred.core.factions;

import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * In plain terms: the rules for founding, joining and running a faction, the same for a person and an AI player: up to
 * ten members, a leader, officers who may invite, kick, set relations and the home, and a leader who alone promotes,
 * hands over the lead, picks the colour or disbands.
 *
 * Technical: Pure rules over the faction ledger (M-06c). Each request returns the events to write (accepted) or the
 * reason it was refused, never a change: the adapter writes the events to the World Record and the ledger folds them.
 * {@code at} carries the tick, the real time and the world the events are stamped with.
 */
public final class Factions {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern TAG = Pattern.compile("[A-Z0-9]{2,4}");

    private Factions() {}

    /** When and where a request is made, for the events it writes. */
    public record At(long tick, long realTimeMs, String world) {}

    /** A request's outcome: the events to write, or why it was refused. */
    public record Decision(List<WorldEvent> events, String reason) {
        public Decision { events = List.copyOf(events); }
        public boolean accepted() { return !events.isEmpty(); }
        static Decision refused(String reason) { return new Decision(List.of(), reason); }
        static Decision ok(String reason, WorldEvent... events) { return new Decision(List.of(events), reason); }
    }

    /** {@code player} founds a faction, its leader. */
    public static Decision create(FactionBook book, UUID player, String name, String tag, FactionColour colour, UUID newId, At at) {
        if (book.of(player).isPresent()) return Decision.refused("You are already in a faction");
        if (name == null || !NAME.matcher(name).matches()) return Decision.refused("A faction's name is 3 to 16 letters, digits or _");
        String upper = tag == null ? "" : tag.toUpperCase(java.util.Locale.ROOT);
        if (!TAG.matcher(upper).matches()) return Decision.refused("A faction's tag is 2 to 4 letters or digits");
        if (book.named(name).isPresent() || book.named(upper).isPresent()) return Decision.refused("That name or tag is taken");
        if (book.factions().values().stream().anyMatch(f -> f.tag().equalsIgnoreCase(name) || f.name().equalsIgnoreCase(upper))) {
            return Decision.refused("That name or tag is taken");
        }
        var data = new LinkedHashMap<String, String>();
        data.put("name", name);
        data.put("tag", upper);
        data.put("colour", (colour == null ? FactionColour.WHITE : colour).id());
        return Decision.ok("Founded " + name + " [" + upper + "]", event(at, FactionLedger.CREATE, player, newId, data));
    }

    /** An officer or the leader invites {@code player}; or takes the invitation back. */
    public static Decision invite(FactionBook book, UUID by, UUID player, boolean add, At at) {
        var faction = book.of(by).orElse(null);
        if (faction == null) return Decision.refused("You are not in a faction");
        if (!faction.members().get(by).atLeast(Faction.Rank.OFFICER)) return Decision.refused("Only an officer or the leader invites");
        if (add && faction.members().containsKey(player)) return Decision.refused("Already a member");
        if (add && book.invited(faction.id(), player)) return Decision.refused("Already invited");
        if (!add && !book.invited(faction.id(), player)) return Decision.refused("Not invited");
        return Decision.ok(add ? "Invited" : "Invitation withdrawn",
                event(at, FactionLedger.INVITE, by, faction.id(), Map.of("player", player.toString(), "add", add ? "1" : "0")));
    }

    /** {@code player} joins a faction that invited it, if it has room. */
    public static Decision join(FactionBook book, UUID player, UUID factionId, FactionSettings settings, At at) {
        if (book.of(player).isPresent()) return Decision.refused("You are already in a faction");
        var faction = book.get(factionId).orElse(null);
        if (faction == null) return Decision.refused("No such faction");
        if (!book.invited(factionId, player)) return Decision.refused("Not invited to " + faction.name());
        if (faction.members().size() >= settings.maxMembers()) return Decision.refused(faction.name() + " is full (" + settings.maxMembers() + " members)");
        return Decision.ok("Joined " + faction.name(), event(at, FactionLedger.JOIN, player, factionId, Map.of()));
    }

    /** {@code player} leaves; a leader leaves only alone, and its faction is disbanded with it. */
    public static Decision leave(FactionBook book, UUID player, At at) {
        var faction = book.of(player).orElse(null);
        if (faction == null) return Decision.refused("You are not in a faction");
        if (faction.leader().equals(player)) {
            if (faction.members().size() > 1) return Decision.refused("Hand the lead to another member first, or disband");
            return Decision.ok("Left; " + faction.name() + " is disbanded", event(at, FactionLedger.DISBAND, player, faction.id(), Map.of()));
        }
        return Decision.ok("Left " + faction.name(), event(at, FactionLedger.LEAVE, player, faction.id(), Map.of()));
    }

    /** An officer kicks a member, or the leader kicks an officer or a member. */
    public static Decision kick(FactionBook book, UUID by, UUID player, At at) {
        var faction = book.of(by).orElse(null);
        if (faction == null) return Decision.refused("You are not in a faction");
        var target = faction.members().get(player);
        if (target == null) return Decision.refused("Not a member");
        var rank = faction.members().get(by);
        if (by.equals(player)) return Decision.refused("Leave instead");
        if (!rank.atLeast(Faction.Rank.OFFICER) || !rank.atLeast(target) || rank == target) return Decision.refused("Your rank cannot kick theirs");
        return Decision.ok("Kicked", eventAs(at, FactionLedger.LEAVE, player, faction.id(), Map.of("by", by.toString())));
    }

    /** The leader sets a member's rank; naming another leader hands over the lead. */
    public static Decision rank(FactionBook book, UUID by, UUID player, Faction.Rank rank, At at) {
        var faction = book.of(by).orElse(null);
        if (faction == null) return Decision.refused("You are not in a faction");
        if (!faction.leader().equals(by)) return Decision.refused("Only the leader sets ranks");
        if (!faction.members().containsKey(player)) return Decision.refused("Not a member");
        if (player.equals(by)) return Decision.refused("You are the leader");
        if (faction.members().get(player) == rank) return Decision.refused("Already " + rank.name().toLowerCase(java.util.Locale.ROOT));
        return Decision.ok(rank == Faction.Rank.LEADER ? "The lead handed over" : "Rank set",
                event(at, FactionLedger.RANK, by, faction.id(), Map.of("player", player.toString(), "rank", rank.name())));
    }

    /** An officer declares what its faction wishes to be to another: enemy, truce or ally. */
    public static Decision wish(FactionBook book, UUID by, UUID other, Faction.Relation relation, At at) {
        var faction = book.of(by).orElse(null);
        if (faction == null) return Decision.refused("You are not in a faction");
        if (!faction.members().get(by).atLeast(Faction.Rank.OFFICER)) return Decision.refused("Only an officer or the leader sets relations");
        if (book.get(other).isEmpty() || other.equals(faction.id())) return Decision.refused("No such other faction");
        if (faction.wishes().getOrDefault(other, Faction.Relation.ENEMY) == relation) return Decision.refused("Already so");
        return Decision.ok("Declared " + relation.name().toLowerCase(java.util.Locale.ROOT),
                event(at, FactionLedger.WISH, by, faction.id(), Map.of("other", other.toString(), "relation", relation.name())));
    }

    /** An officer sets the faction's home at {@code place}; the territory rules (M-06d) decide where that may be. */
    public static Decision home(FactionBook book, UUID by, String place, At at) {
        var faction = book.of(by).orElse(null);
        if (faction == null) return Decision.refused("You are not in a faction");
        if (!faction.members().get(by).atLeast(Faction.Rank.OFFICER)) return Decision.refused("Only an officer or the leader sets the home");
        return Decision.ok("Home set", new WorldEvent(0, at.tick(), at.realTimeMs(), at.world(), FactionLedger.HOME, by, faction.id().toString(),
                place, Map.of(), WorldEvent.HowKnown.RECORDED));
    }

    /** The leader chooses the faction's colour from the sixteen dye colours. */
    public static Decision colour(FactionBook book, UUID by, FactionColour colour, At at) {
        var faction = book.of(by).orElse(null);
        if (faction == null) return Decision.refused("You are not in a faction");
        if (!faction.leader().equals(by)) return Decision.refused("Only the leader chooses the colour");
        return Decision.ok("Colour set", event(at, FactionLedger.COLOUR, by, faction.id(), Map.of("colour", colour.id())));
    }

    /** The leader disbands the faction. */
    public static Decision disband(FactionBook book, UUID by, At at) {
        var faction = book.of(by).orElse(null);
        if (faction == null) return Decision.refused("You are not in a faction");
        if (!faction.leader().equals(by)) return Decision.refused("Only the leader disbands");
        return Decision.ok("Disbanded " + faction.name(), event(at, FactionLedger.DISBAND, by, faction.id(), Map.of()));
    }

    /** The members of a faction, for the faction channel and the rules. */
    public static List<UUID> members(FactionBook book, UUID faction) {
        return book.get(faction).map(f -> new ArrayList<>(f.members().keySet())).map(List::copyOf).orElse(List.of());
    }

    private static WorldEvent event(At at, String kind, UUID actor, UUID faction, Map<String, String> data) {
        return WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), kind, actor, faction.toString(), "", data);
    }

    /** An event about {@code player} (who is its actor) made by someone else, named in its data. */
    private static WorldEvent eventAs(At at, String kind, UUID player, UUID faction, Map<String, String> data) {
        return event(at, kind, player, faction, data);
    }
}
