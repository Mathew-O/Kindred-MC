package dev.kindred.fabric;

import dev.kindred.core.ConversationAudience;
import dev.kindred.core.social.Claims;
import dev.kindred.core.social.PeerAnswers;
import dev.kindred.core.social.PeerLedger;
import dev.kindred.core.social.PeerUpdate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: how each AI player comes to know the others. Once a second it notes who it can see; every five seconds
 * each teammate's own update (what it is doing, needs and could spare, and where it is) reaches the others through the
 * team, never as chat; what a person says of themselves is kept as a claim, and taken back when they say so; what the game
 * saw happen between them (a blow, a gift, a toss) goes in too. What it knows is kept with the world across restarts,
 * apart from what was said and where people were.
 *
 * Technical: E-50's adapter: one {@link PeerLedger} per AI player in this world, fed on the server thread, read by the
 * voice (its situation) and by the fixed replies ({@link PeerAnswers}); loaded lazily from and saved to
 * {@code <uuid>.peers.json} through {@link PlayerMemoryStore}, off the server thread. The runtime says who its players are
 * and builds each one's update from its own state; teammates are AI players out of any match, as in co-op, and share
 * places. Nothing here moves a body, speaks, or calls a model.
 */
final class PeerRadio {
    /** How far an AI player notes who it can see: as far as it talks. */
    static final double SIGHT = 16;
    /** Ticks between teammates' updates: five seconds. */
    static final long UPDATE_TICKS = 100;
    /** Ticks between saves of a ledger: a minute. */
    static final long SAVE_TICKS = 1_200;
    /** Lines of what it knows of others given to the voice. */
    static final int TOLD = 6;

    /** One AI player as the runtime sees it now: its body (null when it has none in the world), whether it is in a match, and its own update. */
    record Member(UUID id, String name, ServerPlayerEntity body, boolean inMatch, PeerUpdate update) { }

    /** Who a speaker is to an AI player, for what it may be told (E-20a's tiers). */
    record Audience(ConversationAudience.Relation relation, boolean controls) { }

    /** The runtime's side. */
    interface Source {
        /** The AI player a body belongs to, or null for a person. */
        UUID aiPlayerOf(net.minecraft.entity.Entity body);

        /** Who {@code speaker} is to the AI player {@code id}, or null when it cannot be told anything now. */
        Audience audience(UUID id, ServerPlayerEntity speaker);

        /** Sends an AI player's own update to its team through the Wire (F-34c), which tells it to each teammate. */
        void sendState(PeerUpdate update);
    }

    private final PlayerMemoryStore store;
    private final MinecraftServer server;
    private final Source source;
    private final Map<UUID, PeerLedger> ledgers = new HashMap<>();
    /** How each AI player stands with those it knows (E-24a), read and kept beside its ledger. */
    private final Map<UUID, dev.kindred.core.social.Rapport> rapports = new HashMap<>();
    private final Set<UUID> loading = new HashSet<>();
    private final Map<UUID, String> names = new HashMap<>();
    /** Each AI player's own last update to its team, as it was sent. */
    private final Map<UUID, PeerUpdate> lastUpdates = new HashMap<>();
    private long lastUpdate = -UPDATE_TICKS, lastSave; // Not Long.MIN_VALUE: now minus that overflows, and no update would go.

    PeerRadio(PlayerMemoryStore store, MinecraftServer server, Source source) {
        this.store = store;
        this.server = server;
        this.source = source;
    }

    /** This AI player's ledger, once it has been read; empty while it is being read. */
    Optional<PeerLedger> ledger(UUID id) {
        return Optional.ofNullable(ledgers.get(id));
    }

    /** A second's turn: who each AI player can see; every five seconds, the teammates' updates; every minute, a save. */
    void tick(long now, List<Member> members) {
        for (Member m : members) {
            names.put(m.id(), m.name());
            if (m.update() != null) lastUpdates.put(m.id(), m.update());
            if (!ledgers.containsKey(m.id()) && loading.add(m.id())) load(m.id(), m.name());
        }
        for (Member m : members) {
            PeerLedger ledger = ledgers.get(m.id());
            if (ledger == null || m.body() == null || !m.body().isAlive()) continue;
            ledger.sighting(inSight(m.body()), now);
        }
        if (now - lastUpdate >= UPDATE_TICKS) {
            lastUpdate = now;
            for (Member from : members) {
                if (from.update() != null) source.sendState(from.update()); // The Wire decides who is a teammate: a team, or a match side.
            }
        }
        if (now - lastSave >= SAVE_TICKS) {
            lastSave = now;
            saveAll();
        }
    }

    /** Everyone it can see from {@code body}: people and other AI players within sight, each where it stands. */
    private List<PeerLedger.Sighting> inSight(ServerPlayerEntity body) {
        var out = new ArrayList<PeerLedger.Sighting>();
        String dimension = body.getServerWorld().getRegistryKey().getValue().toString();
        for (ServerPlayerEntity other : body.getServerWorld().getPlayers()) {
            if (other == body || !other.isAlive() || other.isSpectator() || other.squaredDistanceTo(body) > SIGHT * SIGHT) continue;
            if (!SocialPerception.visible(body, other)) continue;
            UUID ai = source.aiPlayerOf(other);
            var place = new PeerLedger.Place(dimension, other.getBlockX(), other.getBlockY(), other.getBlockZ());
            out.add(ai != null ? new PeerLedger.Sighting(ai, names.getOrDefault(ai, other.getName().getString()), PeerLedger.Kind.AI_PLAYER, place)
                    : new PeerLedger.Sighting(other.getUuid(), other.getName().getString(), PeerLedger.Kind.PERSON, place));
        }
        return out;
    }

    private void load(UUID id, String name) {
        store.loadPeers(id, name).whenComplete((snapshot, failure) -> server.execute(() -> {
            loading.remove(id);
            if (ledgers.containsKey(id)) return;
            ledgers.put(id, PeerLedger.restore(id, failure == null ? snapshot : null));
        }));
        store.loadRapport(id, name).whenComplete((snapshot, failure) -> server.execute(() -> {
            if (!rapports.containsKey(id)) rapports.put(id, dev.kindred.core.social.Rapport.restore(id, failure == null ? snapshot : null));
        }));
    }

    /** How the AI player stands with those it knows (E-24a), once read; empty while it is being read. */
    Optional<dev.kindred.core.social.Rapport> rapport(UUID id) {
        return Optional.ofNullable(rapports.get(id));
    }

    /**
     * Something that happened between the AI player and {@code other}, from the game's record (a gift picked up, a blow, an
     * outing done), kept once under {@code key} (E-24a). Never anything said.
     */
    void note(UUID id, UUID other, String name, dev.kindred.core.social.Rapport.Kind kind, String detail, String key) {
        var rapport = rapports.get(id);
        if (rapport == null || id.equals(other)) return;
        rapport.note(other, name, kind, detail, key, "game", server.getTicks()).ifPresent(event ->
                KindredMod.LOGGER.info("{} notes with {}: {} {} ({}; friendship {})", names.getOrDefault(id, "?"), name, kind.name().toLowerCase(java.util.Locale.ROOT),
                        detail, key, rapport.standing(other).friendship()));
    }

    /** What a person has said they need, to this AI player, still a claim (E-50), newest first: the words of each. */
    List<String> claimedNeeds(UUID id, UUID person) {
        PeerLedger ledger = ledgers.get(id);
        if (ledger == null) return List.of();
        var out = new java.util.ArrayList<String>();
        var claims = ledger.claims(person, PeerLedger.Topic.NEEDS, server.getTicks());
        for (var claim : claims) out.add(claim.text()); // The ledger gives them newest first.
        return out;
    }

    /** A teammate's update, as the Wire told it to the AI player {@code id} (F-34c). */
    void teamState(UUID id, PeerUpdate update) {
        PeerLedger ledger = ledgers.get(id);
        if (ledger != null && !id.equals(update.from())) ledger.update(update, server.getTicks());
    }

    /** The AI player's own last update to its team (what it is doing, needs, could spare), or empty. */
    Optional<PeerUpdate> lastUpdate(UUID id) {
        return Optional.ofNullable(lastUpdates.get(id));
    }

    /** Every ledger written, as it is now. */
    void saveAll() {
        for (var entry : ledgers.entrySet()) store.savePeers(entry.getKey(), names.getOrDefault(entry.getKey(), entry.getKey().toString()), entry.getValue().snapshot());
        for (var entry : rapports.entrySet()) store.saveRapport(entry.getKey(), names.getOrDefault(entry.getKey(), entry.getKey().toString()), entry.getValue().snapshot());
    }

    /**
     * Something said to the AI player {@code id} by a person who can talk with it: a claim they make of themselves noted,
     * one taken back marked. The reply for a taking-back, or empty for anything else (ordinary talk leaves no note).
     */
    Optional<String> heard(UUID id, ServerPlayerEntity speaker, String message) {
        PeerLedger ledger = ledgers.get(id);
        if (ledger == null || source.aiPlayerOf(speaker) != null) return Optional.empty();
        long now = server.getTicks();
        var taken = Claims.taken(message);
        if (taken.isPresent()) {
            var corrected = ledger.correct(speaker.getUuid(), taken.get().about(), taken.get().how(), now);
            if (corrected.isPresent()) {
                KindredMod.LOGGER.info("{} notes that {} took back \"{}\" ({}; said {} ticks before)", names.getOrDefault(id, "?"),
                        speaker.getName().getString(), corrected.get().text(), corrected.get().correction(), now - corrected.get().at());
                return Optional.of(taken.get().how() == PeerLedger.Correction.WRONG
                        ? "Noted. I won't go by that." : "All right, I've forgotten it.");
            }
            return Optional.of("You haven't told me anything I'm going by.");
        }
        Claims.claim(message).ifPresent(claim -> {
            ledger.claim(speaker.getUuid(), speaker.getName().getString(), claim.topic(), claim.text(), claim.dimension(), now);
            KindredMod.LOGGER.info("{} notes {}'s claim: {} {} (said, not seen)", names.getOrDefault(id, "?"), speaker.getName().getString(),
                    claim.topic().words(), claim.text());
        });
        return Optional.empty();
    }

    /** The AI player's own answer to a question about someone, from what it knows, as this speaker may be told; empty for no such question. */
    Optional<String> answer(UUID id, ServerPlayerEntity speaker, String message) {
        PeerLedger ledger = ledgers.get(id);
        var question = Claims.question(message);
        if (ledger == null || question.isEmpty()) return Optional.empty();
        Audience audience = source.audience(id, speaker);
        if (audience == null) return Optional.empty();
        return PeerAnswers.answer(question.get(), ledger, audience.relation(), audience.controls(), speaker.getName().getString(), server.getTicks());
    }

    /** What the AI player knows of the others, for its voice; the situation tells each listener only what it may. */
    List<PeerLedger.Told> told(UUID id) {
        PeerLedger ledger = ledgers.get(id);
        return ledger == null ? List.of() : ledger.told(server.getTicks(), TOLD);
    }

    /** The game saw something happen between them (E-49's record: a blow, harm after coming where asked, a chest emptied). */
    void episode(UUID id, UUID other, String name, String what) {
        PeerLedger ledger = ledgers.get(id);
        if (ledger == null || id.equals(other)) return;
        ledger.episode(other, name, kind(other), what, server.getTicks());
    }

    /** Items the AI player handed someone, as its toss recorded them. */
    void gave(UUID id, UUID to, String name, String item, int count) {
        PeerLedger ledger = ledgers.get(id);
        if (ledger != null && !id.equals(to)) ledger.gave(to, name, kind(to), item, count, server.getTicks());
    }

    /** Items someone handed the AI player, as its pickup recorded them. */
    void received(UUID id, UUID from, String name, String item, int count) {
        PeerLedger ledger = ledgers.get(id);
        if (ledger != null && !id.equals(from)) ledger.received(from, name, kind(from), item, count, server.getTicks());
    }

    private PeerLedger.Kind kind(UUID who) {
        return names.containsKey(who) ? PeerLedger.Kind.AI_PLAYER : PeerLedger.Kind.PERSON;
    }

    /** An operator's {@code memory forget <their id>}: everything the AI player knows of that person or player removed. */
    boolean forget(UUID id, UUID who) {
        PeerLedger ledger = ledgers.get(id);
        var rapport = rapports.get(id);
        boolean felt = rapport != null && rapport.forget(who); // How it stood with them goes too (E-24a).
        return ledger != null && ledger.forget(who) || felt;
    }

    /**
     * An AI player's own update to its team, from its own record: what it is doing (its goal and where it has got to, or
     * its task), what its follow-up queue still has to gather, what it holds of its finds unpromised, and where it is.
     */
    static PeerUpdate update(UUID id, String name, ServerPlayerEntity body, String doing,
                             List<dev.kindred.core.guide.NeedsAssessment.Chunk> chunks, Map<String, Integer> spare) {
        var needs = new ArrayList<String>();
        for (var chunk : chunks) {
            if (chunk.kind() == dev.kindred.core.guide.NeedsAssessment.Kind.GATHER) needs.add(chunk.count() + " " + chunk.need().words(chunk.count()));
        }
        var spares = new ArrayList<String>();
        spare.forEach((item, count) -> { if (count > 0) spares.add(count + " " + item); });
        var place = new PeerLedger.Place(body.getServerWorld().getRegistryKey().getValue().toString(), body.getBlockX(), body.getBlockY(), body.getBlockZ());
        return new PeerUpdate(id, name, doing, needs, spares, place);
    }
}
