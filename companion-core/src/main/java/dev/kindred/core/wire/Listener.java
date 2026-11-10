package dev.kindred.core.wire;

import dev.kindred.core.social.ListenerPreferences;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: someone who might receive a message: a person or an AI player, with the teams, faction, place and
 * match side they belong to, whether they are an operator, and where they stand (for earshot only).
 *
 * Technical: An immutable observation of one possible receiver, gathered by the adapter from the registries that own
 * each membership (the team coordinator, the factions' ledger, the match registry, the server's operator list); the
 * Wire never reads Minecraft objects. {@code side} is {@code match:side} while the receiver is joined to a running
 * match, else empty (a match without sides gives each player a side of its own, {@code match:player}). {@code preferences} are a person's E-19a settings; ignored for AI players.
 */
public record Listener(UUID id, String name, boolean person, Set<String> teams, String faction, String settlement, String side,
                       boolean operator, Place at, ListenerPreferences preferences) {
    /** A place to measure earshot from. */
    public record Place(String dimension, double x, double y, double z) {
        public Place { Objects.requireNonNull(dimension); }

        public boolean within(Place other, int radius) {
            if (other == null || !dimension.equals(other.dimension)) return false;
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
        }
    }

    public Listener {
        Objects.requireNonNull(id);
        name = name == null ? "" : name;
        teams = teams == null ? Set.of() : Set.copyOf(teams);
        faction = faction == null ? "" : faction;
        settlement = settlement == null ? "" : settlement;
        side = side == null ? "" : side;
        preferences = preferences == null ? ListenerPreferences.defaults(id) : preferences;
    }

    /** The {@code match:side} key. */
    public static String side(String match, String side) { return Objects.requireNonNull(match) + ":" + Objects.requireNonNull(side); }

    /** The match of {@link #side}, empty outside a match. */
    public String match() {
        int at = side.indexOf(':');
        return at < 0 ? "" : side.substring(0, at);
    }

    /** Whether this listener is in the scope's audience, by relation; earshot only for public scopes. */
    public boolean inAudience(Scope scope) {
        return switch (scope.channel().audience()) {
            case PRIVATE -> id.toString().equals(scope.key());
            case TEAM -> teams.contains(scope.key());
            case FACTION -> !faction.isEmpty() && faction.equals(scope.key());
            case SETTLEMENT -> !settlement.isEmpty() && settlement.equals(scope.key());
            case SIDE -> !side.isEmpty() && side.equals(scope.key());
            case MATCH -> !match().isEmpty() && match().equals(scope.key());
            case PUBLIC -> scope.serverWide() || (at != null && at.within(scope.earshot(), scope.radius()));
            case OPERATOR -> operator;
        };
    }
}
