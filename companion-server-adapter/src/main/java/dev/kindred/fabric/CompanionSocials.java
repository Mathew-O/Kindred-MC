package dev.kindred.fabric;

import dev.kindred.core.PeerProgressDialogue;
import dev.kindred.core.AttentionPolicy;
import dev.kindred.core.Mannerisms;
import dev.kindred.core.SocialBrain;
import dev.kindred.core.SpeechTiming;
import dev.kindred.core.SocialDialogue;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.SocialSnapshot;
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

/** Server-thread social lane. No task mutation, navigation, memory invention or model requests. */
final class CompanionSocials {
    record Participant(UUID id, UUID owner, String name, CompanionEntity body, SocialProfile profile, boolean active) { }

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

    private final Function<UUID, Participant> lookup;
    private final LongSupplier clock;
    private final BiConsumer<ServerPlayerEntity, Text> output;
    private final Habits habits;
    private final Map<UUID, SocialBrain> brains = new HashMap<>();
    private final Map<UUID, AttentionPolicy> attention = new HashMap<>();
    private final Map<UUID, Long> directSpeakers = new LinkedHashMap<>();
    private final Map<UUID, Long> listeners = new LinkedHashMap<>();
    private final Map<UUID, Long> recentDamage = new HashMap<>();
    private final Map<String, Long> peerCooldowns = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private long nextAmbient = Long.MIN_VALUE;
    private PeerConferenceHandler conferenceHandler;

    void setConferenceHandler(PeerConferenceHandler handler) {
        this.conferenceHandler = handler;
    }

    private record Pending(Participant speaker, ServerPlayerEntity recipient, Text text, long due, boolean direct) { }

    CompanionSocials(Function<UUID, Participant> lookup, LongSupplier clock,
                     BiConsumer<ServerPlayerEntity, Text> output) {
        this(lookup, clock, output, Habits.NONE);
    }

    CompanionSocials(Function<UUID, Participant> lookup, LongSupplier clock,
                     BiConsumer<ServerPlayerEntity, Text> output, Habits habits) {
        this.lookup = lookup;
        this.clock = clock;
        this.output = output;
        this.habits = habits;
    }

    void reset(UUID id) {
        SocialBrain brain = brains.get(id);
        if (brain != null) brain.clearObservations();
        AttentionPolicy policy = attention.get(id);
        if (policy != null) policy.clear();
        recentDamage.remove(id);
        pending.removeIf(p -> p.speaker().id().equals(id) && !p.direct());
    }

    /** The body is gone: an answer that has not been said is dropped rather than delivered later. */
    void discard(UUID id) {
        reset(id);
        pending.removeIf(p -> p.speaker().id().equals(id));
    }

    /** Say the lines whose reading time has elapsed, and drop any whose listener has walked off. */
    void deliverDue(UUID companionId, long now) {
        pending.removeIf(pendingLine -> {
            if (!pendingLine.speaker().id().equals(companionId) || pendingLine.due() > now) return false;
            if (canTalk(pendingLine.recipient(), pendingLine.speaker())) output.accept(pendingLine.recipient(), pendingLine.text());
            return true;
        });
    }

    void tick(Participant speaker) {
        if (!speaker.active()) return;
        long now = clock.getAsLong();
        if (speaker.body().hurtTime > 0) recentDamage.put(speaker.id(), now + 10_000);
        // Stagger social checks across ticks by companion ID so 10 concurrent bots don't all scan on the same tick
        if ((speaker.body().age + Math.floorMod(speaker.id().hashCode(), 20)) % 20 != 0) return;
        notice(speaker, now);
        maybePeerConference(speaker, now);
        List<ServerPlayerEntity> audience = SocialPerception.listeners(speaker.body(), speaker.profile());
        if (audience.isEmpty()) {
            // Nobody to talk to: drop pending remarks, but keep knowing who is already standing here.
            SocialBrain brain = brains.get(speaker.id());
            if (brain != null) brain.clearObservations();
            recentDamage.remove(speaker.id());
            return;
        }
        var observed = withRecentDamage(speaker.id(), now, SocialPerception.observe(speaker.body(), speaker.profile(), true, body -> {
            Participant other = lookup.apply(body.companionId());
            return eligiblePartner(speaker, other) && brain(other.id()).canSpeak(other.profile(), now);
        }));
        react(speaker, observed, audience, now);
    }

    private void maybePeerConference(Participant speaker, long now) {
        if (conferenceHandler == null) return;
        if (speaker.body().age % 100 != Math.floorMod(speaker.id().hashCode(), 100)) return;
        List<SocialSnapshot.Contact> contacts = SocialPerception.noticeable(speaker.body());
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
        List<SocialSnapshot.Contact> people = SocialPerception.noticeable(speaker.body());
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
                && (companion.owner().equals(player.getUuid()) || companion.profile().publicConversation());
    }

    boolean reply(ServerPlayerEntity player, Participant companion, String message) {
        if (!canTalk(player, companion)) return false;
        long now = clock.getAsLong();
        if (now < directSpeakers.getOrDefault(player.getUuid(), Long.MIN_VALUE)
                || !brain(companion.id()).admitDirectReply(now)) return false;
        remember(directSpeakers, player.getUuid(), now + 3_000);
        // A remark about a habit gets a character answer; it is still finite dialogue with no task or settings effect.
        Optional<String> habitReply = habits.feedback(player, companion, message, now);
        String spoken = habitReply.orElseGet(() -> {
            var observed = withRecentDamage(companion.id(), now,
                    SocialPerception.observe(companion.body(), companion.profile(), companion.active(), ignored -> false));
            return SocialDialogue.reply(message, observed, companion.profile());
        });
        schedule(companion, player, spoken, now + SpeechTiming.delayMillis(spoken), true);
        return true;
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
        return new SocialSnapshot(snapshot.active(), snapshot.raining(), snapshot.night(),
                snapshot.hurt() || now < recentDamage.getOrDefault(id, Long.MIN_VALUE), snapshot.visibleThreat(), snapshot.contacts());
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
