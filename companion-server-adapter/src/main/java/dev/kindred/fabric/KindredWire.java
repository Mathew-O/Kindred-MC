package dev.kindred.fabric;

import dev.kindred.core.match.GameModes;
import dev.kindred.core.social.ListenerPreferences;
import dev.kindred.core.wire.Delivery;
import dev.kindred.core.wire.Listener;
import dev.kindred.core.wire.Message;
import dev.kindred.core.wire.Wire;
import dev.kindred.core.wire.Wording;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * In plain terms: the server's end of the Wire. When a message is sent it looks at who is on the server now (people
 * and AI players, with their teams, faction, place and side of a match), lets the Wire decide who gets it, hands each
 * AI player what it was told, and says it in plain words to each person who should hear it, and to nobody else.
 *
 * Technical: F-34b. Server thread only; no IO, no model call. The roster is built from the server's player list and
 * the runtime's {@link Memberships} (the team coordinator, the match registry, and the factions' ledger once Session
 * A plugs it in), never from Minecraft objects inside core. {@link Delivery#told} goes to the {@link Knowledge} sink
 * and a bounded inbox per AI player (sixty-four messages); {@link Delivery#phrase} is worded by {@link Wording} and
 * sent to that person alone through {@link Speaker}. Every delivery is logged by counts.
 */
final class KindredWire {
    /**
     * The Factions mode's announcements to the whole server (M-06e's notices, the spec's notifications): few and fixed,
     * past the public channel's budget, never deduped away. Sent only by the server.
     */
    static final Set<String> FACTION_ANNOUNCEMENTS = Set.of("faction.vulnerable", "faction.secure", "faction.capture", "faction.shield");

    /** Messages kept in an AI player's inbox. */
    static final int INBOX = 64;

    /** Who someone belongs to: its teams, faction, settlement and match side ({@code match:side}), each empty for none. */
    record Belongs(Set<String> teams, String faction, String settlement, String side) {
        static final Belongs NONE = new Belongs(Set.of(), "", "", "");
    }

    /** The runtime's side. */
    interface Memberships {
        /** The AI player a body belongs to, or null for a person. */
        UUID aiPlayerOf(ServerPlayerEntity body);

        /** What {@code id} (the AI player's own id, or the person's) belongs to, its body {@code body}. */
        Belongs of(UUID id, ServerPlayerEntity body);

        /** A person's settings (E-19a); defaults for anyone unknown. */
        default ListenerPreferences preferences(UUID person) { return ListenerPreferences.defaults(person); }

        /** The world's mode, null for none chosen. */
        GameModes.WorldMode mode();
    }

    /** Where an AI receiver's knowledge goes: what it was told, the receiver as the Wire saw it, and whether a person sent it. */
    interface Knowledge { void told(Delivery.Told told, Listener receiver, boolean senderIsPerson); }

    /** How a line reaches one person. */
    interface Speaker { void say(ServerPlayerEntity person, String words); }

    private final MinecraftServer server;
    private final Wire wire;
    private final Wording wording;
    private final Memberships memberships;
    private final Knowledge knowledge;
    private final Speaker speaker;
    private final LongSupplier now;
    private final Map<UUID, ArrayDeque<Delivery.Told>> inboxes = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private long sent, refused, toldCount, spoken, stateTold;
    /** Where teammates' updates go (F-34c): each receiver's peer ledger. Never the inbox, the web or the World Record. */
    private java.util.function.BiConsumer<UUID, dev.kindred.core.social.PeerUpdate> stateSink = (r, u) -> { };
    /** Where a delivery to AI players is kept in the World Record (F-32b), or nowhere. */
    private java.util.function.Consumer<dev.kindred.core.record.WorldEvent> recorder = e -> { };

    /** Keeps each delivery to AI players in the World Record through {@code record}, so what they were told survives a restart. */
    void recordTo(java.util.function.Consumer<dev.kindred.core.record.WorldEvent> record) {
        recorder = record == null ? e -> { } : record;
    }

    KindredWire(MinecraftServer server, Wire wire, Wording wording, Memberships memberships, Knowledge knowledge, Speaker speaker, LongSupplier now) {
        this.server = server;
        this.wire = Objects.requireNonNull(wire);
        this.wording = Objects.requireNonNull(wording);
        this.memberships = Objects.requireNonNull(memberships);
        this.knowledge = knowledge == null ? (t, r, p) -> { } : knowledge;
        this.speaker = speaker == null ? KindredWire::chat : speaker;
        this.now = Objects.requireNonNull(now);
    }

    /** The plain way a person is told: a line in their own chat, seen by nobody else. */
    static void chat(ServerPlayerEntity person, String words) { person.sendMessage(Text.literal(words), false); }

    Wire wire() { return wire; }

    Wording wording() { return wording; }

    /** Everyone on the server now, as the Wire sees them. */
    List<Listener> roster() {
        var out = new ArrayList<Listener>();
        for (ServerPlayerEntity body : server.getPlayerManager().getPlayerList()) {
            if (body.isRemoved()) continue;
            UUID ai = memberships.aiPlayerOf(body);
            UUID id = ai != null ? ai : body.getUuid();
            Belongs b = memberships.of(id, body);
            if (b == null) b = Belongs.NONE;
            names.put(id, body.getName().getString());
            var at = new Listener.Place(body.getServerWorld().getRegistryKey().getValue().toString(), body.getX(), body.getY(), body.getZ());
            boolean operator = ai == null && server.getPlayerManager().isOperator(body.getGameProfile());
            out.add(new Listener(id, body.getName().getString(), ai == null, b.teams(), b.faction(), b.settlement(), b.side(), operator, at,
                    ai == null ? memberships.preferences(id) : null));
        }
        return out;
    }

    /** Sends a message to whoever on the server now may get it. */
    Delivery send(Message message) {
        var roster = roster();
        Delivery d = wire.send(message, memberships.mode(), roster, now.getAsLong());
        if (!d.sent()) {
            refused++;
            KindredMod.LOGGER.debug("Wire refused {} on {}: {}", message.kind(), message.scope().channel().id(), d.refused().orElseThrow());
            return d;
        }
        sent++;
        Map<UUID, Listener> byId = new HashMap<>();
        for (Listener l : roster) byId.put(l.id(), l);
        Listener sender = message.fromServer() ? null : byId.get(message.from());
        boolean senderIsPerson = sender != null && sender.person();
        var state = wire.config().state().contains(message.kind()) ? dev.kindred.core.wire.TeamState.update(message) : java.util.Optional.<dev.kindred.core.social.PeerUpdate>empty();
        if (state.isPresent()) { // Twelve a minute per player: the receiver's ledger keeps the latest, nothing else does.
            for (Delivery.Told t : d.told()) {
                stateSink.accept(t.receiver(), state.get());
                stateTold++;
            }
            return d;
        }
        for (Delivery.Told t : d.told()) {
            var inbox = inboxes.computeIfAbsent(t.receiver(), k -> new ArrayDeque<>());
            inbox.addLast(t);
            while (inbox.size() > INBOX) inbox.removeFirst();
            knowledge.told(t, byId.get(t.receiver()), senderIsPerson);
            toldCount++;
        }
        if (!d.told().isEmpty()) {
            var first = byId.get(d.told().getFirst().receiver());
            var data = dev.kindred.core.knowledge.WebFeed.record(message, first, senderIsPerson, d.told().stream().map(Delivery.Told::receiver).toList());
            recorder.accept(new dev.kindred.core.record.WorldEvent(0, server.getTicks(), System.currentTimeMillis(), "", dev.kindred.core.knowledge.WebFeed.MESSAGE,
                    message.from(), message.scope().channel().id() + ":" + message.scope().key(), "", data,
                    senderIsPerson ? dev.kindred.core.record.WorldEvent.HowKnown.CLAIMED : dev.kindred.core.record.WorldEvent.HowKnown.TOLD));
        }
        Map<UUID, ServerPlayerEntity> bodies = new HashMap<>();
        for (ServerPlayerEntity body : server.getPlayerManager().getPlayerList()) bodies.put(body.getUuid(), body);
        String from = message.fromServer() ? "" : names.getOrDefault(message.from(), "");
        for (Delivery.Phrase p : d.phrase()) {
            ServerPlayerEntity person = bodies.get(p.person());
            String words = wording.words(message, from);
            if (person == null || words == null) continue;
            speaker.say(person, words);
            spoken++;
        }
        KindredMod.LOGGER.debug("Wire {} on {}: {} told, {} spoken, {} quiet, {} withheld", message.kind(), message.scope().channel().id(),
                d.told().size(), d.phrase().size(), d.quiet().size(), d.withheld().size());
        return d;
    }

    /** What an AI player has been told, oldest first, at most {@link #INBOX}. */
    List<Delivery.Told> inbox(UUID ai) {
        var inbox = inboxes.get(ai);
        return inbox == null ? List.of() : List.copyOf(inbox);
    }

    /** What an AI player has been told of one kind, newest first. */
    List<Delivery.Told> inbox(UUID ai, String kind) {
        var out = new ArrayList<Delivery.Told>();
        for (var t : inbox(ai).reversed()) if (t.message().kind().equals(kind)) out.add(t);
        return out;
    }

    /** Messages held in all the inboxes (the month-long cost check). */
    long inboxed() {
        long n = 0;
        for (var q : inboxes.values()) n += q.size();
        return n;
    }

    /** Sent, refused, told and spoken counts since start. */
    long[] counts() { return new long[] {sent, refused, toldCount, spoken}; }

    /** The id the wire knows someone by, for a body: the AI player's own id, else the person's. */
    UUID idOf(ServerPlayerEntity body) {
        UUID ai = memberships.aiPlayerOf(body);
        return ai != null ? ai : body.getUuid();
    }

    /** A fresh Wire with the defaults, these announcements, and the teammates' updates as its state kind (F-34c). */
    static Wire defaultWire(Set<String> announcements) {
        return new Wire(Wire.Config.defaults().withAnnouncements(announcements).withState(dev.kindred.core.wire.TeamState.KINDS));
    }

    /**
     * An AI player's own update to its team (F-34c, E-50): sent on the first team it belongs to, to that team's AI players
     * alone; in a match, on its own side (a manhunt's hunters to hunters, runners to runners: the match service's own
     * membership, through {@link #belongs}). Nothing when it is on neither, or the world's mode has no such channel.
     */
    Delivery sendState(dev.kindred.core.social.PeerUpdate update) {
        for (Listener l : roster()) {
            if (!l.id().equals(update.from())) continue;
            return dev.kindred.core.wire.TeamState.scopeOf(l).map(scope -> send(dev.kindred.core.wire.TeamState.of(update, scope))).orElse(null);
        }
        return null;
    }

    /**
     * Team talk's facts from the AI player {@code from} (F-34c), on its team's scope (in a match on no team, its side's),
     * as {@code build} makes the message for that scope; null when it is on neither or not on the roster.
     */
    Delivery sendTeam(UUID from, java.util.function.Function<dev.kindred.core.wire.Scope, Message> build) {
        for (Listener l : roster()) {
            if (!l.id().equals(from)) continue;
            return dev.kindred.core.wire.TeamState.scopeOf(l).map(scope -> send(build.apply(scope))).orElse(null);
        }
        return null;
    }

    /** Where a teammate's update goes once the Wire delivers it: the receiving AI player and the update. */
    void onState(java.util.function.BiConsumer<UUID, dev.kindred.core.social.PeerUpdate> sink) {
        stateSink = sink == null ? (r, u) -> { } : sink;
    }

    /** Teammates' updates delivered since start: told to a receiver, kept only as its ledger's latest from that sender. */
    long stateTold() { return stateTold; }

    /**
     * What a player belongs to in the runtime: an AI player out of any match the world's team, as teammates are in
     * co-op, and in a Factions world its faction's own team ({@code world:<faction>}), so no team's news crosses a
     * faction; in a running manhunt its hunters' or runners' side; in another running match a side of its own
     * ({@code match:body}); its faction as the Factions mode says (empty for none).
     */
    static Belongs belongs(boolean ai, java.util.Optional<dev.kindred.core.match.MatchRegistry.Match> running, UUID body, String faction) {
        String side = running.map(m -> m.mode() == dev.kindred.core.match.MatchRegistry.Mode.MANHUNT
                ? Listener.side(m.id().toString(), m.runners().contains(body) ? "runners" : "hunters")
                : Listener.side(m.id().toString(), body.toString())).orElse("");
        String f = faction == null ? "" : faction;
        return new Belongs(ai && running.isEmpty() ? Set.of(f.isEmpty() ? TEAM : TEAM + ":" + f) : Set.of(), f, "", side);
    }

    /** The team every AI player out of a match shares, as in co-op. */
    static final String TEAM = "world";

    /** A memberships stand-in that knows nothing: for code paths before the runtime plugs its own in. */
    static Memberships none(Function<ServerPlayerEntity, UUID> aiPlayerOf) {
        return new Memberships() {
            @Override public UUID aiPlayerOf(ServerPlayerEntity body) { return aiPlayerOf.apply(body); }
            @Override public Belongs of(UUID id, ServerPlayerEntity body) { return Belongs.NONE; }
            @Override public GameModes.WorldMode mode() { return null; }
        };
    }
}
