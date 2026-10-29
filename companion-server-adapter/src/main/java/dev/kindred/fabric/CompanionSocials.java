package dev.kindred.fabric;

import dev.kindred.core.PeerProgressDialogue;
import dev.kindred.core.AttentionPolicy;
import dev.kindred.core.Mannerisms;
import dev.kindred.core.SocialBrain;
import dev.kindred.core.SpeechTiming;
import dev.kindred.core.SocialDialogue;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.SocialSnapshot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Server-thread social lane. No task mutation, navigation, memory invention or model requests. Its speakers are AI
 * players in either body, the legacy companion's or the native player's.
 */
final class CompanionSocials {
    record Participant(UUID id, UUID owner, String name, LivingEntity body, SocialProfile profile, boolean active) { }

    interface PeerConferenceHandler {
        void onConference(Participant speaker, Participant peer, PeerProgressDialogue.Exchange exchange);
        PeerProgressDialogue.PeerState state(UUID id);
    }

    /**
     * Runtime-owned habit state for a companion. Everything here is bounded by the core policies; a glance is
     * a presentation request on an existing lease, and a feedback reply never changes a saved setting.
     */
    interface Habits {
        /** A reply to a remark about one of the companion's habits, or empty when the message is ordinary small talk. */
        Optional<String> feedback(ServerPlayerEntity speaker, Participant companion, String message, long now);
        /** The current activity phase, or null when nothing is in progress that could be paused. */
        AttentionPolicy.Phase phase(UUID companionId);
        /** Whether the active skill needs the gaze channel; a glance is then reduced to a head-free acknowledgement. */
        boolean gazeOwned(UUID companionId);
        Set<Mannerisms.Quirk> activeQuirks(UUID companionId, long now);
        /** Perform the bounded physical glance; the attention policy has already admitted it. */
        void glance(UUID companionId, UUID subject, long durationMillis, long now);

        Habits NONE = new Habits() {
            @Override public Optional<String> feedback(ServerPlayerEntity speaker, Participant companion, String message, long now) { return Optional.empty(); }
            @Override public AttentionPolicy.Phase phase(UUID companionId) { return null; }
            @Override public boolean gazeOwned(UUID companionId) { return true; }
            @Override public Set<Mannerisms.Quirk> activeQuirks(UUID companionId, long now) { return Set.of(); }
            @Override public void glance(UUID companionId, UUID subject, long durationMillis, long now) { }
        };
    }

    /**
     * The player's voice (E-20a): a reply phrased by the model from what the player knows that this listener may know.
     * Words only; whatever it returns goes through {@link #schedule} and the same checks as a fixed reply.
     */
    interface Voice {
        /**
         * Ask for a line answering {@code message}, handing it to {@code answered} on the server thread, or empty for the
         * fixed reply; false at once, {@code answered} never called, when it cannot ask now (off, budgets, rates).
         */
        boolean answer(ServerPlayerEntity speaker, Participant player, String message, java.util.function.Consumer<Optional<Spoken>> answered);

        /** A direct reply was said to {@code speaker}, the model's or the fixed one: the exchange as it went. */
        default void replied(ServerPlayerEntity speaker, Participant player, String said, String spoken) { }
    }

    /**
     * A voiced line, and what follows once it is said (E-20b: an ask, handed a way to say one more line to the same
     * listener, as a refusal in the command's own words). The lane only says the words; what follows is the voice's.
     */
    record Spoken(String text, java.util.function.Consumer<java.util.function.Consumer<String>> then) {
        Spoken {
            java.util.Objects.requireNonNull(text);
        }
    }

    /** A message that came while a line was being phrased: only the newest waits. */
    private record Waiting(ServerPlayerEntity speaker, String message) { }

    private final Function<UUID, Participant> lookup;
    private final LongSupplier clock;
    private final BiConsumer<ServerPlayerEntity, Text> output;
    private final Habits habits;
    private final Map<UUID, SocialBrain> brains = new HashMap<>();
    private final Map<UUID, AttentionPolicy> attention = new HashMap<>();
    private final Map<UUID, Long> directSpeakers = new LinkedHashMap<>();
    private final Map<UUID, Long> listeners = new LinkedHashMap<>();
    private final Map<UUID, Long> recentDamage = new HashMap<>();
    /** What last hurt each speaker, as the game recorded it, kept with {@link #recentDamage}. */
    private final Map<UUID, SocialSnapshot.Harm> recentHarm = new HashMap<>();
    /** The AI player a body belongs to, or null for a person or anything else. */
    private final Function<Entity, UUID> aiPlayer;
    private final Map<String, Long> peerCooldowns = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private long nextAmbient = Long.MIN_VALUE;
    private PeerConferenceHandler conferenceHandler;
    private Voice voice;
    /** Whether a person may command an AI player: such a one is as good as its owner in conversation (F-18a). */
    private java.util.function.BiPredicate<ServerPlayerEntity, Participant> controls = (player, companion) -> false;
    /** Players with a line being phrased, by the turn of their talk it answers; a turn ended by discard is not said. */
    private final Map<UUID, Long> voicing = new HashMap<>();
    private final Map<UUID, Waiting> waiting = new HashMap<>();
    private long turns;

    /**
     * Who may command each AI player, as the runtime's access rules say: they are talked with as its owner is, in
     * private (F-18a). A native player's own record is its identity, so without this nobody was its owner here.
     */
    void setControllers(java.util.function.BiPredicate<ServerPlayerEntity, Participant> controls) {
        this.controls = controls;
    }

    /** The player's voice, or null for the fixed replies alone. */
    void setVoice(Voice voice) {
        this.voice = voice;
    }

    void setConferenceHandler(PeerConferenceHandler handler) {
        this.conferenceHandler = handler;
    }

    /**
     * A line to say when due; a voiced one ({@code voiced}) is checked at delivery against the player as it is then, and
     * what follows it ({@code then}) happens only once it is said.
     */
    private record Pending(Participant speaker, ServerPlayerEntity recipient, Text text, long due, boolean direct, boolean voiced,
                           java.util.function.Consumer<java.util.function.Consumer<String>> then) {
        Pending(Participant speaker, ServerPlayerEntity recipient, Text text, long due, boolean direct) {
            this(speaker, recipient, text, due, direct, false, null);
        }
    }

    CompanionSocials(Function<UUID, Participant> lookup, LongSupplier clock,
                     BiConsumer<ServerPlayerEntity, Text> output) {
        this(lookup, clock, output, Habits.NONE);
    }

    CompanionSocials(Function<UUID, Participant> lookup, LongSupplier clock,
                     BiConsumer<ServerPlayerEntity, Text> output, Habits habits) {
        this(lookup, clock, output, habits, SocialPerception::companionOf);
    }

    CompanionSocials(Function<UUID, Participant> lookup, LongSupplier clock,
                     BiConsumer<ServerPlayerEntity, Text> output, Habits habits, Function<Entity, UUID> aiPlayer) {
        this.lookup = lookup;
        this.clock = clock;
        this.output = output;
        this.habits = habits;
        this.aiPlayer = aiPlayer;
    }

    void reset(UUID id) {
        SocialBrain brain = brains.get(id);
        if (brain != null) brain.clearObservations();
        AttentionPolicy policy = attention.get(id);
        if (policy != null) policy.clear();
        recentDamage.remove(id);
        recentHarm.remove(id);
        pending.removeIf(p -> p.speaker().id().equals(id) && !p.direct());
    }

    /** The body is gone: an answer that has not been said is dropped rather than delivered later. */
    void discard(UUID id) {
        reset(id);
        pending.removeIf(p -> p.speaker().id().equals(id));
        voicing.remove(id);
        waiting.remove(id);
    }

    /** Say the lines whose reading time has elapsed, and drop any whose listener has walked off. */
    void deliverDue(UUID companionId, long now) {
        var said = new ArrayList<Runnable>();
        pending.removeIf(pendingLine -> {
            if (!pendingLine.speaker().id().equals(companionId) || pendingLine.due() > now) return false;
            // A voiced line was asked for a while back: it is said only if the player, as it is now, may still say it.
            Participant speaker = pendingLine.voiced() ? lookup.apply(companionId) : pendingLine.speaker();
            if (canTalk(pendingLine.recipient(), speaker)) {
                output.accept(pendingLine.recipient(), pendingLine.text());
                if (pendingLine.then() != null) said.add(() -> pendingLine.then().accept(follow ->
                        scheduleInTurn(speaker, pendingLine.recipient(), follow, clock.getAsLong() + SpeechTiming.delayMillis(follow), null)));
            }
            return true;
        });
        said.forEach(Runnable::run); // What follows a line, once the lines are said.
    }

    void tick(Participant speaker) {
        if (!speaker.active()) return;
        long now = clock.getAsLong();
        if (speaker.body().hurtTime > 0) {
            recentDamage.put(speaker.id(), now + 10_000);
            var harm = SocialPerception.harm(speaker.body().getRecentDamageSource(), aiPlayer);
            if (harm != null) recentHarm.put(speaker.id(), harm);
        }
        // Stagger social checks across ticks by companion ID so 10 concurrent bots don't all scan on the same tick
        if ((speaker.body().age + Math.floorMod(speaker.id().hashCode(), 20)) % 20 != 0) return;
        notice(speaker, now);
        maybePeerConference(speaker, now);
        List<ServerPlayerEntity> audience = SocialPerception.listeners(speaker.body(), owns(speaker), speaker.profile(), aiPlayer);
        if (audience.isEmpty()) {
            // Nobody to talk to: drop pending remarks, but keep knowing who is already standing here.
            SocialBrain brain = brains.get(speaker.id());
            if (brain != null) brain.clearObservations();
            recentDamage.remove(speaker.id());
            recentHarm.remove(speaker.id());
            return;
        }
        var observed = withRecentDamage(speaker.id(), now, SocialPerception.observe(speaker.body(), owns(speaker), speaker.profile(), true,
                aiPlayer, body -> {
                    UUID id = aiPlayer.apply(body);
                    Participant other = id == null ? null : lookup.apply(id);
                    return eligiblePartner(speaker, other) && brain(other.id()).canSpeak(other.profile(), now);
                }));
        react(speaker, observed, audience, now);
    }

    private void maybePeerConference(Participant speaker, long now) {
        if (conferenceHandler == null) return;
        if (speaker.body().age % 100 != Math.floorMod(speaker.id().hashCode(), 100)) return;
        List<SocialSnapshot.Contact> contacts = SocialPerception.noticeable(speaker.body(), owns(speaker), aiPlayer);
        for (var contact : contacts) {
            if (contact.kind() != SocialSnapshot.Kind.COMPANION) continue;
            Participant peer = lookup.apply(contact.id());
            if (peer == null || !peer.active() || !SocialPerception.visible(speaker.body(), peer.body())) continue;

            String pairKey = speaker.id().compareTo(peer.id()) < 0
                    ? speaker.id() + ":" + peer.id()
                    : peer.id() + ":" + speaker.id();
            Long nextAllowed = peerCooldowns.get(pairKey);
            if (nextAllowed != null && now < nextAllowed) continue;

            PeerProgressDialogue.PeerState sState = conferenceHandler.state(speaker.id());
            PeerProgressDialogue.PeerState pState = conferenceHandler.state(peer.id());
            if (sState == null || pState == null) continue;

            long seed = now ^ speaker.id().getMostSignificantBits();
            var exchangeOpt = PeerProgressDialogue.confer(sState, pState, seed);
            if (exchangeOpt.isPresent()) {
                peerCooldowns.put(pairKey, now + 120_000); // 2 minutes cooldown between this pair
                conferenceHandler.onConference(speaker, peer, exchangeOpt.orElseThrow());
                break;
            }
        }
    }

    /** Notice-and-return: a bounded glance at a newcomer, with an optional low-priority remark queued for later. */
    private void notice(Participant speaker, long now) {
        AttentionPolicy.Phase phase = habits.phase(speaker.id());
        if (phase == null) return;
        List<SocialSnapshot.Contact> people = SocialPerception.noticeable(speaker.body(), owns(speaker), aiPlayer);
        Set<UUID> ids = new HashSet<>();
        for (var contact : people) ids.add(contact.id());
        var glance = attention.computeIfAbsent(speaker.id(), ignored -> new AttentionPolicy()).observe(ids, phase, true,
                habits.gazeOwned(speaker.id()), speaker.profile().personality(), habits.activeQuirks(speaker.id(), now), now);
        if (glance.isEmpty()) return;
        var admitted = glance.orElseThrow();
        if (admitted.durationMillis() > 0) habits.glance(speaker.id(), admitted.subject(), admitted.durationMillis(), now);
        if (!admitted.remark()) return;
        people.stream().filter(contact -> contact.id().equals(admitted.subject())).findFirst()
                .ifPresent(contact -> brain(speaker.id()).noticed(contact, activity(phase), speaker.profile(), now));
    }

    private static String activity(AttentionPolicy.Phase phase) {
        return switch (phase) {
            case BROWSING_CONTAINER -> "going through this chest";
            case SORTING_INVENTORY -> "sorting my things";
            case WALKING_UNCOMMITTED -> "on my way";
            case WAITING -> "waiting here";
            default -> "minding my own business";
        };
    }

    /** Synchronous observation-to-speech boundary; every recipient and partner is rechecked here. */
    void react(Participant speaker, SocialSnapshot observed, List<ServerPlayerEntity> audience, long now) {
        if (!speaker.active()) return;
        SocialBrain brain = brain(speaker.id());
        audience = audience.stream().filter(player -> canTalk(player, speaker)).toList();
        var proposal = brain.observe(observed, speaker.profile(), now);
        if (proposal.isEmpty() || now < nextAmbient) return;
        SocialBrain.Speech speech = proposal.orElseThrow();
        Participant partner = speech.topic() == SocialBrain.Topic.COMPANION ? lookup.apply(speech.subject()) : null;
        if (speech.topic() == SocialBrain.Topic.COMPANION) {
            if (!eligiblePartner(speaker, partner) || !brain(partner.id()).canSpeak(partner.profile(), now)) return;
            audience = audience.stream().filter(player -> SocialPerception.visible(partner.body(), player)).toList();
        }
        List<ServerPlayerEntity> recipients = audience.stream()
                .filter(player -> now >= listeners.getOrDefault(player.getUuid(), Long.MIN_VALUE)).toList();
        if (recipients.isEmpty()) return;
        for (ServerPlayerEntity player : recipients) {
            long due = now + SpeechTiming.delayMillis(speech.text());
            schedule(speaker, player, speech.text(), due, false);
            if (partner != null) {
                String answer = "Hi, " + speaker.name() + ". Good to see you too.";
                schedule(partner, player, answer, due + SpeechTiming.delayMillis(answer), false);
            }
            remember(listeners, player.getUuid(), now + 30_000);
        }
        brain.didSpeak(speech, speaker.profile(), now);
        if (partner != null) brain(partner.id()).didReplyToCompanion(speaker.id(), partner.profile(), now);
        nextAmbient = now + 10_000;
        // An exchange is terminal: system chat never enters the player-chat handler.
    }

    boolean canTalk(ServerPlayerEntity player, Participant companion) {
        return companion != null && SocialPerception.visible(companion.body(), player)
                && (owns(companion).test(player) || companion.profile().publicConversation());
    }

    /** Its owners in the lane: its saved owner, and whoever may command it (F-18a). */
    private java.util.function.Predicate<ServerPlayerEntity> owns(Participant companion) {
        return player -> companion.owner().equals(player.getUuid()) || controls.test(player, companion);
    }

    boolean reply(ServerPlayerEntity player, Participant companion, String message) {
        if (!canTalk(player, companion)) return false;
        long now = clock.getAsLong();
        if (now < directSpeakers.getOrDefault(player.getUuid(), Long.MIN_VALUE)
                || !brain(companion.id()).admitDirectReply(now)) return false;
        remember(directSpeakers, player.getUuid(), now + 3_000);
        // A remark about a habit gets a character answer; it is still finite dialogue with no task or settings effect.
        Optional<String> habitReply = habits.feedback(player, companion, message, now);
        if (habitReply.isEmpty() && voice != null) {
            if (voicing.containsKey(companion.id())) { // One line phrased at a time: this one waits, the newest in place of any waiting.
                waiting.put(companion.id(), new Waiting(player, message));
                return true;
            }
            if (voiced(player, companion, message, now)) return true;
        }
        String spoken = habitReply.orElseGet(() -> fixedReply(companion, message, now));
        schedule(companion, player, spoken, now + SpeechTiming.delayMillis(spoken), true);
        if (voice != null) voice.replied(player, companion, message, spoken);
        return true;
    }

    private String fixedReply(Participant companion, String message, long now) {
        var observed = withRecentDamage(companion.id(), now,
                SocialPerception.observe(companion.body(), owns(companion), companion.profile(), companion.active(), aiPlayer, ignored -> false));
        return SocialDialogue.reply(message, observed, companion.profile());
    }

    /**
     * Ask the voice to phrase the reply. Its line, or the fixed reply when it has none, is scheduled when it comes, the
     * time it took counted as typing; then the message that waited meanwhile, if any, is answered in turn.
     */
    private boolean voiced(ServerPlayerEntity player, Participant companion, String message, long asked) {
        long turn = ++turns;
        voicing.put(companion.id(), turn);
        boolean asking = voice.answer(player, companion, message, line -> {
            if (!Long.valueOf(turn).equals(voicing.get(companion.id()))) return; // Discarded meanwhile: nothing is said.
            voicing.remove(companion.id());
            Participant now = lookup.apply(companion.id());
            long at = clock.getAsLong();
            if (now != null && canTalk(player, now)) {
                String spoken = line.map(Spoken::text).orElseGet(() -> fixedReply(now, message, at));
                var then = line.map(Spoken::then).orElse(null);
                // A line that acts is said as it acts, at once; any other once it would have been typed.
                scheduleInTurn(now, player, spoken, then != null ? at : at + Math.max(0, SpeechTiming.delayMillis(spoken) - (at - asked)), then);
                voice.replied(player, now, message, spoken);
            }
            Waiting next = waiting.remove(companion.id());
            if (next != null && now != null && canTalk(next.speaker(), now)) answerWaiting(next, now, at);
        });
        if (!asking && Long.valueOf(turn).equals(voicing.get(companion.id()))) voicing.remove(companion.id());
        return asking;
    }

    /** The message that waited: phrased in turn if the voice can ask now, else the fixed reply. */
    private void answerWaiting(Waiting next, Participant companion, long now) {
        if (voice != null && voiced(next.speaker(), companion, next.message(), now)) return;
        String spoken = fixedReply(companion, next.message(), now);
        scheduleInTurn(companion, next.speaker(), spoken, now + SpeechTiming.delayMillis(spoken), null);
        if (voice != null) voice.replied(next.speaker(), companion, next.message(), spoken);
    }

    /** A reply after a voiced exchange: said after any reply to the same listener not yet said, never in its place. */
    private void scheduleInTurn(Participant speaker, ServerPlayerEntity recipient, String spoken, long due,
                                java.util.function.Consumer<java.util.function.Consumer<String>> then) {
        for (Pending p : pending) {
            if (p.direct() && p.speaker().id().equals(speaker.id()) && p.recipient().getUuid().equals(recipient.getUuid())) due = Math.max(due, p.due() + 1);
        }
        pending.add(new Pending(speaker, recipient, line(speaker, spoken), due, true, true, then));
        while (pending.size() > 64) pending.removeFirst();
    }

    private void schedule(Participant speaker, ServerPlayerEntity recipient, String spoken, long due, boolean direct) {
        if (direct) pending.removeIf(p -> p.direct() && p.speaker().id().equals(speaker.id()) && p.recipient().getUuid().equals(recipient.getUuid()));
        pending.add(new Pending(speaker, recipient, line(speaker, spoken), due, direct));
        while (pending.size() > 64) pending.removeFirst();
    }

    static boolean eligiblePartner(Participant a, Participant b) {
        return b != null && !a.id().equals(b.id()) && a.active() && b.active()
                && a.profile().publicConversation() && b.profile().publicConversation()
                && SocialPerception.visible(a.body(), b.body()) && SocialPerception.visible(b.body(), a.body());
    }

    private SocialBrain brain(UUID id) { return brains.computeIfAbsent(id, ignored -> new SocialBrain()); }

    private SocialSnapshot withRecentDamage(UUID id, long now, SocialSnapshot snapshot) {
        boolean recent = now < recentDamage.getOrDefault(id, Long.MIN_VALUE);
        return new SocialSnapshot(snapshot.active(), snapshot.raining(), snapshot.night(), snapshot.hurt() || recent, snapshot.visibleThreat(),
                snapshot.contacts(), snapshot.harm() != null ? snapshot.harm() : recent ? recentHarm.get(id) : null);
    }

    void workCompleted(UUID companionId, String skill, String detail) {
        SocialBrain b = brain(companionId);
        Participant p = lookup.apply(companionId);
        if (b != null && p != null) {
            b.workCompleted(skill, detail, p.profile(), clock.getAsLong());
        }
    }

    void workBlocked(UUID companionId, String skill, String reason) {
        SocialBrain b = brain(companionId);
        Participant p = lookup.apply(companionId);
        if (b != null && p != null) {
            b.workBlocked(skill, reason, p.profile(), clock.getAsLong());
        }
    }

    private static Text line(Participant speaker, String message) {
        return Text.literal("[Kindred] " + speaker.name() + ": " + message);
    }

    private static void remember(Map<UUID, Long> map, UUID id, long until) {
        map.remove(id);
        map.put(id, until);
        while (map.size() > 512) map.remove(map.keySet().iterator().next());
    }
}
