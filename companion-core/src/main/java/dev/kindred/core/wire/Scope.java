package dev.kindred.core.wire;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: which one of a channel's audiences a message goes to: this person, this team, this faction, this
 * place, this side of this match, everyone near here, the whole server, or the operators.
 *
 * Technical: A channel and its key: the receiver's id for {@link Channel#PRIVATE}; the team, faction or settlement id;
 * {@code match:side} for {@link Channel#SIDE}; for {@link Channel#PUBLIC} an earshot around a place, or the whole
 * server (only for a mode's announcements); nothing for {@link Channel#OPERATOR}.
 */
public record Scope(Channel channel, String key, Listener.Place earshot, int radius) {
    public Scope {
        Objects.requireNonNull(channel);
        key = key == null ? "" : key.strip();
        if (channel == Channel.PUBLIC) {
            if (earshot == null && !key.equals(SERVER)) throw new IllegalArgumentException("A public scope needs an earshot or the server");
            if (earshot != null && radius <= 0) throw new IllegalArgumentException("An earshot needs a radius");
        } else {
            if (key.isEmpty() && channel != Channel.OPERATOR) throw new IllegalArgumentException("A " + channel.id() + " scope needs its key");
            earshot = null;
            radius = 0;
        }
    }

    /** The public key that means the whole server. */
    public static final String SERVER = "server";

    public static Scope toPlayer(UUID receiver) { return new Scope(Channel.PRIVATE, receiver.toString(), null, 0); }
    public static Scope team(String team) { return new Scope(Channel.TEAM, team, null, 0); }
    public static Scope faction(String faction) { return new Scope(Channel.FACTION, faction, null, 0); }
    public static Scope settlement(String place) { return new Scope(Channel.SETTLEMENT, place, null, 0); }
    public static Scope side(String match, String side) { return new Scope(Channel.SIDE, Listener.side(match, side), null, 0); }
    public static Scope earshot(Listener.Place at, int radius) { return new Scope(Channel.PUBLIC, "", at, radius); }
    public static Scope server() { return new Scope(Channel.PUBLIC, SERVER, null, 0); }
    public static Scope operators() { return new Scope(Channel.OPERATOR, "", null, 0); }

    /** Whether this is a whole-server public scope. */
    public boolean serverWide() { return channel == Channel.PUBLIC && earshot == null; }

    /** The budget and dedupe key: the channel and its key, earshot scopes by their speaker's channel alone. */
    String budgetKey() { return channel.id() + "|" + (earshot != null ? "earshot" : key); }
}
