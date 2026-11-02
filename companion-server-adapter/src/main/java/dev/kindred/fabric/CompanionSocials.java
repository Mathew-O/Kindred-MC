package dev.kindred.fabric;

import dev.kindred.core.PeerProgressDialogue;
import dev.kindred.core.AttentionPolicy;
import dev.kindred.core.Mannerisms;
import dev.kindred.core.SocialBrain;
import dev.kindred.core.SpeechTiming;
import dev.kindred.core.SocialDialogue;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.SocialSnapshot;
import dev.kindred.core.social.Expression;
import dev.kindred.core.social.ListenerPreferences;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collection;
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
 * In plain terms: the AI players' small talk on the server: glancing at people who come near, passing
 * remarks, answering when spoken to (in their own words when the optional voice is on, otherwise with a fixed
 * reply), chatting with each other about how their work is going, and mentioning work done or stuck. It only
 * handles words and glances; what a player does is decided elsewhere.
 *
 * Technical: Server-thread social lane. No task mutation, navigation, memory invention or model requests. Its
 * speakers are AI players in either body, the legacy companion's or the native player's. Every line sounds as the
 * speaker's preset under the server's cap (E-18a) and as the strictest of what its hearers asked for (E-19a).
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

        /** Whether there is a model to ask at all (it may still decline a given line: budgets, rates, a refused ask). */
        default boolean available() { return true; }
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

    /**
     * What the player makes of what is said to it beyond the words (E-50): a claim the speaker makes of themselves noted,
     * one they take back marked, and a question about someone it knows answered from what it knows. Never an ask.
     */
    interface Hearing {
        /** A claim noted or taken back; the reply to a taking-back, or empty. */
        Optional<String> heard(ServerPlayerEntity speaker, Participant player, String message);

        /** Its own answer to an ask it has done or refused (E-52), or empty. */
        Optional<String> answer(ServerPlayerEntity speaker, Participant player, String message);

        /**
         * Its own answer to a question about someone it knows, from its ledger (E-50), or empty. With a voice available the
         * voice phrases the answer from the same record (E-50a), and this is said only if the voice does not.
         */
        default Optional<String> about(ServerPlayerEntity speaker, Participant player, String message) { return Optional.empty(); }

        /** With no voice to read it, E-20b's asks in their plain forms (E-52): said, then done; or empty. */
        default Optional<Spoken> fallback(ServerPlayerEntity speaker, Participant player, String message) { return Optional.empty(); }

        Hearing NONE = new Hearing() {
            @Override public Optional<String> heard(ServerPlayerEntity speaker, Participant player, String message) { return Optional.empty(); }
            @Override public Optional<String> answer(ServerPlayerEntity speaker, Participant player, String message) { return Optional.empty(); }
        };
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
    /** The most expressive any player may sound on this server (E-18a). */
    private Expression cap = Expression.ROUGH;
    /** Whether a person who said nothing hears swearing and dark jokes: the operator's say for the server (E-18b). */
    private boolean roughAudience;
    /** What each person asked of how AI players speak to them (E-19a), by person; anyone absent asked for nothing. */
    private final Map<UUID, ListenerPreferences> preferences = new HashMap<>();
    /** False while the saved preferences are still being read: nothing unasked is said, and no jokes or teasing. */
    private boolean preferencesKnown = true;
    private Hearing hearing = Hearing.NONE;

    /**
     * Who may command each AI player, as the runtime's access rules say: they are talked with as its owner is, in
     * private (F-18a). A native player's own record is its identity, so without this nobody was its owner here.
     */
    void setControllers(java.util.function.BiPredicate<ServerPlayerEntity, Participant> controls) {
        this.controls = controls;
    }

    /** What the player makes of what is said to it (E-50). */
    void setHearing(Hearing hearing) {
        this.hearing = java.util.Objects.requireNonNull(hearing);
    }

    /** The player's voice, or null for the fixed replies alone. */
    void setVoice(Voice voice) {
        this.voice = voice;
    }

    /** The most expressive any player may sound on this server (E-18a); null is no cap below playful. */
    /** The operator's say for people who said nothing: swearing and dark jokes heard by everyone who did not say no (E-18b). */
    void setRoughAudience(boolean everyone) { this.roughAudience = everyone; }

    void setExpressionCap(Expression cap) {
        this.cap = cap == null ? Expression.ROUGH : cap;
    }

    Expression expressionCap() { return cap; }

    /** The saved preferences are being read: until {@link #loadPreferences}, everyone is taken to want the least. */
    void awaitPreferences() {
        preferencesKnown = false;
    }

    /** Each person's saved preferences, read at startup; none at all when they could not be read. */
    void loadPreferences(Collection<ListenerPreferences> saved) {
        preferences.clear();
        for (ListenerPreferences p : saved) if (p.person() != null && !p.isDefault()) preferences.put(p.person(), p);
        preferencesKnown = true;
    }

    /**
     * What this person asked of how they are spoken to: their own, the defaults if they asked for nothing, and, while the
     * saved ones are still being read, no jokes and no teasing.
     */
    ListenerPreferences preferences(UUID person) {
        ListenerPreferences own = preferences.get(person);
        if (own != null) return own.resolved(roughAudience);
        return (preferencesKnown ? ListenerPreferences.defaults(person) : new ListenerPreferences(person, ListenerPreferences.Unasked.USUAL, true, true, 0))
                .resolved(roughAudience && preferencesKnown);
    }

    /** What this person saved, as they set it (the server's default not read in), for {@code /aiplayer prefs}. */
    ListenerPreferences ownPreferences(UUID person) {
        ListenerPreferences own = preferences.get(person);
        return own != null ? own : ListenerPreferences.defaults(person);
    }

    /** Whether the saved preferences have been read. */
    boolean preferencesKnown() { return preferencesKnown; }

    /** The AI player a body belongs to, or null for a person. */
    UUID aiPlayerOf(Entity body) { return aiPlayer.apply(body); }

    /** A person changed or deleted their preferences; they hold from the next line. */
    void preferencesChanged(ListenerPreferences changed) {
        if (changed.isDefault()) preferences.remove(changed.person());
        else preferences.put(changed.person(), changed);
    }

    /** How the player sounds here, for another lane's words (E-53). */
    Expression soundingOf(Participant player) { return sounding(player).effectiveExpression(); }

    /** How the player sounds here: its preset, or its humour with none, bounded by the server's cap (E-18a). */
    private SocialProfile sounding(Participant player) { return player.profile().capped(cap); }

    /**
     * The one line the voice is told about how to speak to this listener: the player's preset under the cap, and what the
     * listener asked for (E-18a, E-19a).
     */
    String manner(Participant player, ServerPlayerEntity listener) {
        SocialProfile sounds = sounding(player);
        return Expression.voiceLine(sounds.effectiveExpression(), preferences(listener.getUuid()), sounds.flirty(), sounds.effectiveChatStyle());
    }

    /** Whether the voice's line to this listener may swear: a rough player, to someone who said yes to it (E-18b). */
    boolean maySwear(Participant player, ServerPlayerEntity listener) {
        return sounding(player).effectiveExpression() == Expression.ROUGH && preferences(listener.getUuid()).swears();
    }

    /**
     * The last check of every line an AI player says, from every lane and the voice alike (E-18b), made as it is
     * delivered, so a person who has just changed their settings is spoken to as they now asked: a line with a word
     * never said is dropped; a swear word is softened for anyone who did not say yes to it or from a player that is not
     * rough; then the line is typed in the player's own chat style. Null when nothing is to be said.
     */
    String finalWords(Participant speaker, ServerPlayerEntity recipient, String text) {
        if (text == null || text.isBlank()) return null;
        if (dev.kindred.core.social.Decency.blocked(text)) {
            KindredMod.LOGGER.warn("{}'s line to {} dropped: words never said", speaker.name(), recipient.getName().getString());
            return null;
        }
        SocialProfile sounds = sounding(speaker);
        boolean swears = sounds.effectiveExpression() == Expression.ROUGH && preferences(recipient.getUuid()).swears();
        String words = swears ? text : dev.kindred.core.social.Decency.soften(text);
        return typed(sounds, speaker.id(), words, namesOn(recipient));
    }

    /** A line typed in a player's chat style (E-18b), its names kept as they are. */
    static String typed(SocialProfile profile, UUID id, String text, java.util.List<String> keep) {
        if (profile == null) return text;
        return profile.effectiveChatStyle().apply(text, id, keep, text == null ? 0 : text.hashCode());
    }

    /** The names of everyone on the server, kept as they are typed when a line is lower-cased. */
    static java.util.List<String> namesOn(ServerPlayerEntity anyone) {
        var server = anyone == null ? null : anyone.getServer();
        if (server == null) return java.util.List.of();
        return server.getPlayerManager().getPlayerList().stream().map(p -> p.getName().getString()).distinct().toList();
    }

    /** Who handles two AI players comparing notes on their progress when they meet. */
    void setConferenceHandler(PeerConferenceHandler handler) {
        this.conferenceHandler = handler;
    }

    /**
     * A line to say when due; a voiced one ({@code voiced}) is checked at delivery against the player as it is then, and
     * what follows it ({@code then}) happens only once it is said.
     */
    private record Pending(Participant speaker, ServerPlayerEntity recipient, String text, long due, boolean direct, boolean voiced,
                           java.util.function.Consumer<java.util.function.Consumer<String>> then) {
        Pending(Participant speaker, ServerPlayerEntity recipient, String text, long due, boolean direct) {
            this(speaker, recipient, text, due, direct, false, null);
        }
    }

    /**
     * The small talk, finding each AI player by id, keeping time by the given clock and saying lines through
     * the given output.
     */
    CompanionSocials(Function<UUID, Participant> lookup, LongSupplier clock,
                     BiConsumer<ServerPlayerEntity, Text> output) {
        this(lookup, clock, output, Habits.NONE);
    }

    /** As above, with the players' habits. */
    CompanionSocials(Function<UUID, Participant> lookup, LongSupplier clock,
                     BiConsumer<ServerPlayerEntity, Text> output, Habits habits) {
        this(lookup, clock, output, habits, SocialPerception::companionOf);
    }

    /** As above, with a way to tell which body belongs to which AI player. */
    CompanionSocials(Function<UUID, Participant> lookup, LongSupplier clock,
                     BiConsumer<ServerPlayerEntity, Text> output, Habits habits, Function<Entity, UUID> aiPlayer) {
        this.lookup = lookup;
        this.clock = clock;
        this.output = output;
        this.habits = habits;
        this.aiPlayer = aiPlayer;
    }

    /**
     * Forgets what the player had noticed and drops remarks it had not made yet; replies already due are
     * kept.
     */
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
            String words = canTalk(pendingLine.recipient(), speaker) ? finalWords(speaker, pendingLine.recipient(), pendingLine.text()) : null;
            if (words != null) {
                output.accept(pendingLine.recipient(), line(speaker, words));
                if (pendingLine.then() != null) said.add(() -> pendingLine.then().accept(follow ->
                        scheduleInTurn(speaker, pendingLine.recipient(), follow, clock.getAsLong() + SpeechTiming.delayMillis(follow), null)));
            }
            return true;
        });
        said.forEach(Runnable::run); // What follows a line, once the lines are said.
    }

    /**
     * Once a tick for an active player: notes when it is hurt, and now and then looks about, meets fellow AI
     * players and makes a remark.
     */
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
                .ifPresent(contact -> brain(speaker.id()).noticed(contact, activity(phase), sounding(speaker), now));
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
        SocialProfile sounding = sounding(speaker);
        var proposal = brain.observe(observed, sounding, now);
        if (proposal.isEmpty() || now < nextAmbient || !preferencesKnown) return;
        SocialBrain.Speech speech = proposal.orElseThrow();
        Participant partner = speech.topic() == SocialBrain.Topic.COMPANION ? lookup.apply(speech.subject()) : null;
        if (speech.topic() == SocialBrain.Topic.COMPANION) {
            if (!eligiblePartner(speaker, partner) || !brain(partner.id()).canSpeak(partner.profile(), now)) return;
            audience = audience.stream().filter(player -> SocialPerception.visible(partner.body(), player)).toList();
        }
        List<ServerPlayerEntity> recipients = audience.stream()
                .filter(player -> now >= listeners.getOrDefault(player.getUuid(), Long.MIN_VALUE)).toList();
        if (recipients.isEmpty()) return;
        // Everyone who will hear the line: the strictest of what they asked for decides how it is put (E-19a).
        String text = speech.textFor(ListenerPreferences.strictest(recipients.stream().map(player -> preferences(player.getUuid())).toList()));
        for (ServerPlayerEntity player : recipients) {
            long due = now + SpeechTiming.delayMillis(text);
            schedule(speaker, player, text, due, false);
            if (partner != null) {
                String answer = "Hi, " + speaker.name() + ". Good to see you too.";
                schedule(partner, player, answer, due + SpeechTiming.delayMillis(answer), false);
            }
            remember(listeners, player.getUuid(), now + preferences(player.getUuid()).spacing(UNASKED_SPACING_MILLIS));
        }
        brain.didSpeak(speech, sounding, now);
        if (partner != null) brain(partner.id()).didReplyToCompanion(speaker.id(), partner.profile(), now);
        nextAmbient = now + 10_000;
        // An exchange is terminal: system chat never enters the player-chat handler.
    }

    /**
     * A line the player says first to one person (E-53), its budget already checked by the caller: said through the
     * lane's own checks and typing delay, as any unasked line; false when it may not talk with them now.
     */
    boolean unasked(Participant speaker, ServerPlayerEntity to, String text, long now) {
        if (!speaker.active() || !preferencesKnown || !canTalk(to, speaker)) return false;
        schedule(speaker, to, text, now + SpeechTiming.delayMillis(text), false);
        return true;
    }

    /** The usual pause before a person is spoken to unasked again; their own preference doubles or halves it (E-19a). */
    static final long UNASKED_SPACING_MILLIS = 30_000;

    /**
     * Whether the player can talk with this person: in sight, and either one who may command it or the player
     * talks in public.
     */
    boolean canTalk(ServerPlayerEntity player, Participant companion) {
        return companion != null && SocialPerception.visible(companion.body(), player)
                && (owns(companion).test(player) || companion.profile().publicConversation());
    }

    /** Its owners in the lane: its saved owner, and whoever may command it (F-18a). */
    private java.util.function.Predicate<ServerPlayerEntity> owns(Participant companion) {
        return player -> companion.owner().equals(player.getUuid()) || controls.test(player, companion);
    }

    /**
     * Answers a person who spoke to the player, if it can talk with them now; false when it does not answer.
     */
    boolean reply(ServerPlayerEntity player, Participant companion, String message) {
        if (!canTalk(player, companion)) return false;
        // Heard before any limit on replies (E-50): a claim noted, or one taken back.
        Optional<String> taken = hearing.heard(player, companion, message);
        long now = clock.getAsLong();
        if (now < directSpeakers.getOrDefault(player.getUuid(), Long.MIN_VALUE)
                || !brain(companion.id()).admitDirectReply(now)) return false;
        remember(directSpeakers, player.getUuid(), now + 3_000);
        // A question about someone it knows (E-50): with a voice to phrase it, the voice answers from the same record, told
        // by tier, and costed as any reply (E-50a); its own words from the record if the voice does not.
        Optional<String> known = taken.isPresent() ? Optional.empty() : hearing.about(player, companion, message);
        boolean phrased = known.isPresent() && voice != null && voice.available();
        // Its own words, without the voice, for a taking-back and for what it knows when nothing phrases it; a remark about a
        // habit gets a character answer. Each is finite dialogue with no task or settings effect.
        Optional<String> habitReply = phrased ? taken : taken.or(() -> known).or(() -> hearing.answer(player, companion, message))
                .or(() -> habits.feedback(player, companion, message, now));
        if (habitReply.isEmpty() && voice != null) {
            if (voicing.containsKey(companion.id())) { // One line phrased at a time: this one waits, the newest in place of any waiting.
                Waiting displaced = waiting.put(companion.id(), new Waiting(player, message));
                // Someone else's waiting message is not dropped unanswered: they are given the fixed reply now (E-20c).
                if (displaced != null && !displaced.speaker().getUuid().equals(player.getUuid())) {
                    String spoken = fixedReply(companion, displaced.speaker(), displaced.message(), now);
                    scheduleInTurn(companion, displaced.speaker(), spoken, now + SpeechTiming.delayMillis(spoken), null);
                    voice.replied(displaced.speaker(), companion, displaced.message(), spoken);
                }
                return true;
            }
            if (voiced(player, companion, message, now)) return true;
        }
        if (habitReply.isEmpty() && (voice == null || !voice.available())) {
            // No model to read it at all: an ask in its plain form, said and then done (E-52). A model that declined the
            // line (its budget, or an ask it refused) is not second-guessed here: the fixed reply is said, as before.
            var asked = hearing.fallback(player, companion, message);
            if (asked.isPresent()) {
                scheduleInTurn(companion, player, asked.get().text(), now + SpeechTiming.delayMillis(asked.get().text()), asked.get().then());
                if (voice != null) voice.replied(player, companion, message, asked.get().text());
                return true;
            }
        }
        String spoken = habitReply.or(() -> known).orElseGet(() -> fixedReply(companion, player, message, now));
        schedule(companion, player, spoken, now + SpeechTiming.delayMillis(spoken), true);
        if (voice != null) voice.replied(player, companion, message, spoken);
        return true;
    }

    /** The fixed reply to this listener: as the player sounds under the cap, and plain to one who asked for no jokes. */
    private String fixedReply(Participant companion, ServerPlayerEntity listener, String message, long now) {
        var observed = withRecentDamage(companion.id(), now,
                SocialPerception.observe(companion.body(), owns(companion), companion.profile(), companion.active(), aiPlayer, ignored -> false));
        SocialProfile sounding = sounding(companion);
        if (preferences(listener.getUuid()).noJokes()) sounding = sounding.capped(Expression.PLAIN);
        return SocialDialogue.reply(message, observed, sounding);
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
                // No line from the voice: what it knows of someone, in its own words (E-50a), or the fixed reply.
                String spoken = line.map(Spoken::text).orElseGet(() -> hearing.about(player, now, message).orElseGet(() -> fixedReply(now, player, message, at)));
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
        String spoken = hearing.about(next.speaker(), companion, next.message()).orElseGet(() -> fixedReply(companion, next.speaker(), next.message(), now));
        scheduleInTurn(companion, next.speaker(), spoken, now + SpeechTiming.delayMillis(spoken), null);
        if (voice != null) voice.replied(next.speaker(), companion, next.message(), spoken);
    }

    /** A reply after a voiced exchange: said after any reply to the same listener not yet said, never in its place. */
    private void scheduleInTurn(Participant speaker, ServerPlayerEntity recipient, String spoken, long due,
                                java.util.function.Consumer<java.util.function.Consumer<String>> then) {
        for (Pending p : pending) {
            if (p.direct() && p.speaker().id().equals(speaker.id()) && p.recipient().getUuid().equals(recipient.getUuid())) due = Math.max(due, p.due() + 1);
        }
        pending.add(new Pending(speaker, recipient, spoken, due, true, true, then));
        while (pending.size() > 64) pending.removeFirst();
    }

    private void schedule(Participant speaker, ServerPlayerEntity recipient, String spoken, long due, boolean direct) {
        if (direct) pending.removeIf(p -> p.direct() && p.speaker().id().equals(speaker.id()) && p.recipient().getUuid().equals(recipient.getUuid()));
        pending.add(new Pending(speaker, recipient, spoken, due, direct));
        while (pending.size() > 64) pending.removeFirst();
    }

    /**
     * Whether two AI players may chat with each other: both active, both talk in public, and each can see the
     * other.
     */
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

    /** Lets the player mention a piece of work it finished. */
    void workCompleted(UUID companionId, String skill, String detail) {
        SocialBrain b = brain(companionId);
        Participant p = lookup.apply(companionId);
        if (b != null && p != null) {
            b.workCompleted(skill, detail, sounding(p), clock.getAsLong());
        }
    }

    /** Lets the player mention a piece of work it could not do, and why. */
    void workBlocked(UUID companionId, String skill, String reason) {
        SocialBrain b = brain(companionId);
        Participant p = lookup.apply(companionId);
        if (b != null && p != null) {
            b.workBlocked(skill, reason, sounding(p), clock.getAsLong());
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
