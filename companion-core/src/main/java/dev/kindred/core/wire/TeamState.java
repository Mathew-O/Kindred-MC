package dev.kindred.core.wire;

import dev.kindred.core.social.PeerLedger;
import dev.kindred.core.social.PeerUpdate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * In plain terms: an AI player's own update to its team (what it is doing, needs, could spare, and where it is) as a
 * message on the team's channel, or in a manhunt on its own side's: every fact that group's alone, for its AI players
 * only, never put into words.
 *
 * Technical: F-34c's team path. E-50's {@link PeerUpdate} encoded as a {@link Wire} state kind ({@link #KIND}) on
 * {@link Channel#TEAM} (or {@link Channel#SIDE}, a match side's), every fact that channel's visibility: {@code name}, {@code doing}, {@code place}
 * ({@code dimension x y z}, absent when the team does not share it), {@code need.0..7}, {@code spare.0..7}. Decoding
 * gives back the same update, sender and all.
 */
public final class TeamState {
    /** The state kind of a teammate's update. */
    public static final String KIND = "team.state";
    /** The state kinds the Wire is configured with. */
    public static final Set<String> KINDS = Set.of(KIND);

    private TeamState() { }

    /** The update {@code update} as a message to the team {@code team}. */
    public static Message of(PeerUpdate update, String team) { return of(update, Scope.team(team)); }

    /** The update {@code update} as a message to a team's or a match side's scope, each fact that channel's own. */
    public static Message of(PeerUpdate update, Scope scope) {
        if (scope.channel() != Channel.TEAM && scope.channel() != Channel.SIDE) throw new IllegalArgumentException("A team's or a side's update only");
        Visibility v = scope.channel().visibility;
        var facts = new LinkedHashMap<String, Fact>();
        facts.put("name", Fact.of(update.name(), v));
        facts.put("doing", Fact.of(update.doing(), v));
        var p = update.place();
        if (p != null) facts.put("place", Fact.of(p.dimension() + " " + p.x() + " " + p.y() + " " + p.z(), v));
        for (int i = 0; i < update.needs().size(); i++) facts.put("need." + i, Fact.of(update.needs().get(i), v));
        for (int i = 0; i < update.spare().size(); i++) facts.put("spare." + i, Fact.of(update.spare().get(i), v));
        return Message.of(KIND, update.from(), scope, facts, List.of());
    }

    /** The update a team state message carries, or empty for any other message. */
    public static Optional<PeerUpdate> update(Message message) {
        if (!KIND.equals(message.kind()) || message.fromServer()) return Optional.empty();
        var f = message.facts();
        var needs = new ArrayList<String>();
        var spare = new ArrayList<String>();
        for (int i = 0; i < PeerUpdate.MAX_ITEMS; i++) {
            if (f.containsKey("need." + i)) needs.add(f.get("need." + i).value());
            if (f.containsKey("spare." + i)) spare.add(f.get("spare." + i).value());
        }
        return Optional.of(new PeerUpdate(message.from(), value(f.get("name")), value(f.get("doing")), needs, spare, place(f.get("place"))));
    }

    private static String value(Fact fact) { return fact == null ? "" : fact.value(); }

    private static PeerLedger.Place place(Fact fact) {
        if (fact == null) return null;
        String[] parts = fact.value().split(" ");
        if (parts.length != 4) return null;
        try {
            return new PeerLedger.Place(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The team a player sends its state to, given the teams it belongs to: its first, or empty for none. */
    public static Optional<String> teamOf(Set<String> teams) {
        return teams == null || teams.isEmpty() ? Optional.empty() : Optional.of(teams.stream().sorted().findFirst().orElseThrow());
    }

    /**
     * Where a player's update goes: its first team; with none, in a match, its own side ({@code match:side}: a manhunt's
     * hunters or runners; in any other match a side of its own, which reaches nobody); empty for neither.
     */
    public static Optional<Scope> scopeOf(Listener player) {
        var team = teamOf(player.teams());
        if (team.isPresent()) return Optional.of(Scope.team(team.get()));
        return player.side().isEmpty() ? Optional.empty() : Optional.of(new Scope(Channel.SIDE, player.side(), null, 0));
    }
}
