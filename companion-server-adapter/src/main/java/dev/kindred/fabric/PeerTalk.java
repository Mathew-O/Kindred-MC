package dev.kindred.fabric;

import dev.kindred.core.social.Conversation;
import dev.kindred.core.social.Expression;
import dev.kindred.core.social.ListenerPreferences;
import dev.kindred.core.social.PeerLedger;
import dev.kindred.core.social.PeerUpdate;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * In plain terms: two AI players talking when they meet again after ten minutes or more apart: two or three lines, said
 * only to the people who can hear them (never to the whole server, and never to another AI player as chat), within a few
 * lines a minute for the whole server and once in two minutes for a pair. A fight, a flight, a bed or a match's countdown
 * stops it. With nobody to hear, they still each note that they met and what about, and nothing is said.
 *
 * Technical: E-51's adapter, replacing the fixed peer conference. A turn a second: the pairs in sight of each other are
 * found, an occasion is a meeting after {@link Conversation#APART_MILLIS} apart, and the talk is planned by {@link
 * Conversation#plan} from each side's own record and its ledger of the other; its turns are said one after another with
 * speech timing, each put as the strictest of its hearers' preferences asks. No model is asked: every turn is local.
 * The runtime's side is a {@link Source}, so that a fixture can drive it on its own clock.
 */
final class PeerTalk {
    /** The runtime's side. */
    interface Source {
        /** The AI players who may talk now, in the social lane's terms. */
        List<CompanionSocials.Participant> players();

        /** What the AI player knows of the others, once read. */
        Optional<PeerLedger> ledger(UUID id);

        /** Its own last update to its team: what it is doing, needs and could spare. */
        Optional<PeerUpdate> update(UUID id);

        /** Fighting, fleeing, sheltering, or in a match's countdown: no talk. */
        boolean busy(UUID id);

        Expression sounding(CompanionSocials.Participant player);

        ListenerPreferences preferences(UUID person);

        /** The game's tick, for the ledgers' notes. */
        long tick();
    }

    /** A talk under way: who, its turns left, and when the next may be said. */
    private static final class Talk {
        final CompanionSocials.Participant a, b;
        final Iterator<Conversation.Turn> turns;
        long nextAt;

        Talk(CompanionSocials.Participant a, CompanionSocials.Participant b, List<Conversation.Turn> turns, long nextAt) {
            this.a = a;
            this.b = b;
            this.turns = turns.iterator();
            this.nextAt = nextAt;
        }
    }

    private final Source source;
    private final CompanionSocials socials;
    private final LongSupplier clock;
    private final Conversation.Chatter chatter;
    private final Map<String, Long> spoken = new HashMap<>();
    private final Map<String, Long> together = new LinkedHashMap<>();
    private final List<Talk> talks = new ArrayList<>();
    private int linesSaid;
    /** The lines of talks said, as "Name: text", the newest last (sixty at most), for fixtures. */
    private final java.util.ArrayDeque<String> said = new java.util.ArrayDeque<>();

    PeerTalk(Source source, CompanionSocials socials, LongSupplier clock, Conversation.Chatter chatter) {
        this.source = source;
        this.socials = socials;
        this.clock = clock;
        this.chatter = chatter;
    }

    /** How many lines between AI players it has said, for fixtures. */
    int linesSaid() { return linesSaid; }

    /** The lines said, as "Name: text", oldest first, for fixtures. */
    List<String> saidForFixtures() { return List.copyOf(said); }

    /** How many talks are under way, for fixtures. */
    int talksUnderWay() { return talks.size(); }

    /** Staged, for fixtures: these two last saw each other {@code ago} milliseconds back. */
    void apartForFixtures(UUID a, UUID b, long ago) {
        together.put(pair(a, b), clock.getAsLong() - ago);
    }

    /** A turn: talks under way go on (or stop), and pairs meeting again after a while apart begin one. */
    void tick() {
        long now = clock.getAsLong();
        talks.removeIf(talk -> !onward(talk, now));
        var players = source.players().stream().filter(p -> p.active() && p.body() != null && p.profile().ambientEnabled()
                && p.profile().personality().sociability() > 0).toList();
        for (int i = 0; i < players.size(); i++) {
            for (int j = i + 1; j < players.size(); j++) {
                var a = players.get(i);
                var b = players.get(j);
                if (!SocialPerception.visible(a.body(), b.body()) || !SocialPerception.visible(b.body(), a.body())) continue;
                String key = pair(a.id(), b.id());
                Long last = together.put(key, now);
                while (together.size() > 256) together.remove(together.keySet().iterator().next());
                if (last == null || now - last < Conversation.APART_MILLIS) continue; // Not after a while apart.
                if (talking(a.id()) || talking(b.id()) || source.busy(a.id()) || source.busy(b.id())) continue;
                begin(a.profile().personality().sociability() >= b.profile().personality().sociability() ? a : b,
                        a.profile().personality().sociability() >= b.profile().personality().sociability() ? b : a, now);
            }
        }
    }

    private boolean talking(UUID id) {
        return talks.stream().anyMatch(t -> t.a.id().equals(id) || t.b.id().equals(id));
    }

    private void begin(CompanionSocials.Participant a, CompanionSocials.Participant b, long now) {
        var plan = Conversation.plan(side(a, b), side(b, a), spoken, now).orElse(null);
        if (plan == null) return;
        boolean heard = !hearers(a).isEmpty() || !hearers(b).isEmpty();
        // The server's chatter spent: nothing said and nothing noted, so the topic is still new another time.
        if (heard && !chatter.may(a.id(), b.id(), plan.turns().size(), now)) {
            KindredMod.LOGGER.info("{} and {} met again; the server's chatter is spent for now (at most {} a minute), nothing said", a.name(), b.name(),
                    chatter.perMinute());
            return;
        }
        Conversation.spoken(plan, spoken, now);
        // Both note that they met and what about, whoever hears it (the game's record of the meeting).
        String about = switch (plan.topic().kind()) {
            case DOING -> "what you were doing";
            case NEEDS -> "what you needed";
            case EPISODE -> "old times";
            case HELLO -> "nothing much";
            case FLIRT -> "small talk";
        };
        note(a, b, "talked: " + about);
        note(b, a, "talked: " + about);
        if (!heard) {
            KindredMod.LOGGER.info("{} and {} met again and talked of {} with nobody to hear: noted, nothing said", a.name(), b.name(), about);
            return;
        }
        chatter.began(a.id(), b.id(), now);
        var talk = new Talk(a, b, plan.turns(), now);
        talks.add(talk);
        onward(talk, now);
    }

    /** The talk's next turn if it is due and allowed; false once it is over or stopped. */
    private boolean onward(Talk talk, long now) {
        if (!talk.turns.hasNext()) return false;
        // A fight, a flight, a bed or a countdown stops it; so does losing sight of each other.
        if (source.busy(talk.a.id()) || source.busy(talk.b.id()) || !talk.a.active() || !talk.b.active()
                || !SocialPerception.visible(talk.a.body(), talk.b.body())) {
            KindredMod.LOGGER.info("{} and {}'s talk stops", talk.a.name(), talk.b.name());
            return false;
        }
        if (now < talk.nextAt || !chatter.mayLine(now)) return true;
        var turn = talk.turns.next();
        var speaker = turn.speaker().equals(talk.a.id()) ? talk.a : talk.b;
        var hearers = hearers(speaker);
        String text = turn.textFor(ListenerPreferences.strictest(hearers.stream().map(p -> source.preferences(p.getUuid())).toList()));
        for (ServerPlayerEntity person : hearers) socials.unasked(speaker, person, text, now);
        chatter.line(now);
        linesSaid++;
        said.addLast(speaker.name() + ": " + text);
        while (said.size() > 60) said.removeFirst();
        KindredMod.LOGGER.info("{} to {} ({} hearing): {}", speaker.name(), turn.speaker().equals(talk.a.id()) ? talk.b.name() : talk.a.name(),
                hearers.size(), text);
        talk.nextAt = now + dev.kindred.core.SpeechTiming.delayMillis(text) + 1_000;
        return talk.turns.hasNext();
    }

    /** The people who can hear {@code speaker}: in earshot and allowed by the lane; never an AI player. */
    private List<ServerPlayerEntity> hearers(CompanionSocials.Participant speaker) {
        if (!(speaker.body().getWorld() instanceof net.minecraft.server.world.ServerWorld world)) return List.of();
        var out = new ArrayList<ServerPlayerEntity>();
        for (ServerPlayerEntity person : world.getPlayers()) {
            if (socials.aiPlayerOf(person) != null) continue; // Never to an AI player as chat.
            if (socials.canTalk(person, speaker)) out.add(person);
        }
        return out;
    }

    private Conversation.Side side(CompanionSocials.Participant self, CompanionSocials.Participant other) {
        var update = source.update(self.id()).orElse(null);
        var ledger = source.ledger(self.id()).orElse(null);
        long tick = source.tick();
        String knownDoing = ledger == null ? "" : ledger.best(other.id(), PeerLedger.Topic.DOING, tick).map(PeerLedger.Note::text).orElse("");
        String knownNeeds = ledger == null ? "" : ledger.best(other.id(), PeerLedger.Topic.NEEDS, tick).map(PeerLedger.Note::text).orElse("");
        String episode = "";
        if (ledger != null) {
            for (var e : ledger.episodes(other.id())) if (!e.text().startsWith("talked:")) episode = e.text(); // A callback, not a talk.
        }
        return new Conversation.Side(self.id(), self.name(), self.profile().personality().sociability(), source.sounding(self),
                update == null ? "" : update.doing(), update == null ? List.of() : update.needs(), update == null ? List.of() : update.spare(),
                knownDoing, knownNeeds, episode, self.profile().flirty());
    }

    private void note(CompanionSocials.Participant self, CompanionSocials.Participant other, String what) {
        source.ledger(self.id()).ifPresent(ledger -> ledger.episode(other.id(), other.name(), PeerLedger.Kind.AI_PLAYER, what, source.tick()));
    }

    private static String pair(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }
}
