package dev.kindred.core.wire;

import dev.kindred.core.match.GameModes;
import dev.kindred.core.social.ListenerPreferences;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: the one way messages travel between players. It decides who gets a message before a word is
 * said: the channel's audience by who they are (a faction's members, one side of a match), never by how near they
 * stand except for words in earshot; and only those who may know every fact in it. AI players learn it as told;
 * people get it in words, a few lines a minute at most, fewer if they asked. Each channel has its own limit, the same
 * message is not sent twice in a while, and nothing an AI player said is ever taken back in as a message.
 *
 * Technical: F-34's pure core. Owner-thread only; the adapter builds the roster from immutable observations and
 * hands {@link Delivery#told} to each AI receiver's knowledge and {@link Delivery#phrase} to the voice. Checks, in
 * order: the echo rule, the world's mode, the sender, the whole-server announcements, the facts against the channel,
 * dedupe, the channel's budget; then the audience and each receiver's visibility, then each person's line budget and
 * own dedupe. Announcements registered in {@link Config#announcements} bypass the channel's budget, never dedupe.
 */
public final class Wire {
    /**
     * The Wire's settings: the mode's announcements (few and fixed), the lines a minute a person hears from the Wire at
     * the usual setting (half for fewer, half again more for more), and the earshot of public words.
     */
    public record Config(Set<String> announcements, int personPerMinute, int earshot) {
        public Config {
            announcements = announcements == null ? Set.of() : Set.copyOf(announcements);
            if (personPerMinute < 1) throw new IllegalArgumentException("A person needs at least a line a minute");
            if (earshot < 1) throw new IllegalArgumentException("Earshot must be positive");
        }

        public static Config defaults() { return new Config(Set.of(), 4, 32); }

        public Config withAnnouncements(Set<String> kinds) { return new Config(kinds, personPerMinute, earshot); }

        int linesFor(ListenerPreferences.Unasked unasked) {
            return switch (unasked == null ? ListenerPreferences.Unasked.USUAL : unasked) {
                case FEWER -> Math.max(1, personPerMinute / 2);
                case USUAL -> personPerMinute;
                case MORE -> personPerMinute + personPerMinute / 2;
            };
        }
    }

    private static final long MINUTE = 60_000;
    private static final long LONGEST = java.util.Arrays.stream(Channel.values()).mapToLong(c -> c.dedupeMillis).max().orElse(MINUTE);

    private final Config config;
    private final Map<String, ArrayDeque<Long>> sentOn = new HashMap<>();
    private final Map<String, Long> lastSame = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> heardBy = new HashMap<>();
    private final Map<String, Long> heardSame = new HashMap<>();
    private long lastPrune = Long.MIN_VALUE;

    public Wire(Config config) { this.config = Objects.requireNonNull(config); }

    public Config config() { return config; }

    /** A public scope around a sender at this Wire's earshot. */
    public Scope earshotOf(Listener.Place at) { return Scope.earshot(at, config.earshot()); }

    /**
     * Sends a message in a world played as {@code mode} (null for none chosen) to whoever of {@code roster} its scope
     * reaches and may know it, at {@code now} milliseconds.
     */
    public Delivery send(Message message, GameModes.WorldMode mode, Collection<Listener> roster, long now) {
        Objects.requireNonNull(message);
        Objects.requireNonNull(roster);
        prune(now);
        Scope scope = message.scope();
        Channel channel = scope.channel();

        Listener sender = null;
        if (!message.fromServer()) {
            for (Listener l : roster) if (l.id().equals(message.from())) sender = l;
        }
        // The echo rule: an AI player's words are never a message, and an AI player has no person's words to send.
        if (message.origin() == Message.Origin.AI_WORDS) return Delivery.refused(message, Delivery.Refusal.ECHO);
        if (message.origin() == Message.Origin.PERSON_WORDS && (sender == null || !sender.person())) {
            return Delivery.refused(message, Delivery.Refusal.ECHO);
        }
        if (!ModeChannels.allows(mode, channel)) return Delivery.refused(message, Delivery.Refusal.MODE);
        if (!message.fromServer()) {
            if (sender == null) return Delivery.refused(message, Delivery.Refusal.UNKNOWN_SENDER);
            boolean group = channel != Channel.PRIVATE && channel != Channel.PUBLIC && channel != Channel.OPERATOR;
            if (group && !sender.inAudience(scope)) return Delivery.refused(message, Delivery.Refusal.NOT_MEMBER);
        }
        boolean announcement = scope.serverWide();
        if (announcement && (!message.fromServer() || !config.announcements().contains(message.kind()))) {
            return Delivery.refused(message, Delivery.Refusal.NOT_ANNOUNCEMENT);
        }
        for (Fact fact : message.facts().values()) {
            if (!carries(channel, fact.visibility(), message.fromServer())) return Delivery.refused(message, Delivery.Refusal.NARROWER_FACT);
        }

        String budgetKey = scope.budgetKey() + (scope.earshot() != null ? "|" + message.from() : "");
        String sameKey = budgetKey + "|" + message.sameness();
        Long last = lastSame.get(sameKey);
        if (last != null && now - last < channel.dedupeMillis) return Delivery.refused(message, Delivery.Refusal.DUPLICATE);
        var times = sentOn.computeIfAbsent(budgetKey, k -> new ArrayDeque<>());
        trim(times, now);
        if (!announcement && times.size() >= channel.perMinute) return Delivery.refused(message, Delivery.Refusal.BUDGET);
        times.addLast(now);
        lastSame.put(sameKey, now);

        var told = new ArrayList<Delivery.Told>();
        var phrase = new ArrayList<Delivery.Phrase>();
        var quiet = new ArrayList<UUID>();
        var withheld = new ArrayList<UUID>();
        HowKnown how = message.origin() == Message.Origin.PERSON_WORDS ? HowKnown.CLAIMED : HowKnown.TOLD;
        for (Listener receiver : roster) {
            if (sender != null && receiver.id().equals(sender.id())) continue;
            if (!receiver.inAudience(scope)) continue;
            if (!mayKnowAll(receiver, sender, message)) {
                withheld.add(receiver.id());
                continue;
            }
            if (!receiver.person()) {
                told.add(new Delivery.Told(receiver.id(), message, how));
                continue;
            }
            String heardKey = receiver.id() + "|" + message.sameness();
            Long heard = heardSame.get(heardKey);
            var lines = heardBy.computeIfAbsent(receiver.id(), k -> new ArrayDeque<>());
            trim(lines, now);
            boolean already = heard != null && now - heard < channel.dedupeMillis;
            boolean spent = !announcement && lines.size() >= config.linesFor(receiver.preferences().unasked());
            if (already || spent) {
                quiet.add(receiver.id());
                continue;
            }
            lines.addLast(now);
            heardSame.put(heardKey, now);
            phrase.add(new Delivery.Phrase(receiver.id(), receiver.name(), message, receiver.preferences()));
        }
        return new Delivery(message, java.util.Optional.empty(), told, phrase, quiet, withheld);
    }

    /**
     * Whether a channel may carry a fact at all: public facts anywhere; anything on the operators' channel; operators'
     * facts nowhere else; a sender's own facts on any channel but public (the server's only privately); a group's
     * facts only on that group's channel or privately (where the receiver's own relation then decides).
     */
    static boolean carries(Channel channel, Visibility fact, boolean fromServer) {
        if (fact == Visibility.PUBLIC || channel == Channel.OPERATOR) return true;
        if (fact == Visibility.OPERATOR) return false;
        if (channel == Channel.PUBLIC) return false;
        if (fact == Visibility.SELF) return channel == Channel.PRIVATE || !fromServer;
        return channel == Channel.PRIVATE || channel.visibility == fact;
    }

    /**
     * Whether this receiver may know every fact: a group's fact only when it shares that group with the sender (for the
     * server, when the channel is that group's own and the receiver is in it); an operators' fact only an operator.
     */
    static boolean mayKnowAll(Listener receiver, Listener sender, Message message) {
        Channel channel = message.scope().channel();
        for (Fact fact : message.facts().values()) {
            boolean ok = switch (fact.visibility()) {
                case PUBLIC, SELF -> true;
                case OPERATOR -> receiver.operator();
                case FACTION, TEAM, SIDE, MATCH, SETTLEMENT -> sender == null
                        ? channel.visibility == fact.visibility()
                        : shares(receiver, sender, fact.visibility());
            };
            if (!ok) return false;
        }
        return true;
    }

    private static boolean shares(Listener a, Listener b, Visibility group) {
        return switch (group) {
            case FACTION -> !a.faction().isEmpty() && a.faction().equals(b.faction());
            case SIDE -> !a.side().isEmpty() && a.side().equals(b.side());
            case MATCH -> !a.match().isEmpty() && a.match().equals(b.match());
            case SETTLEMENT -> !a.settlement().isEmpty() && a.settlement().equals(b.settlement());
            case TEAM -> a.teams().stream().anyMatch(b.teams()::contains);
            default -> false;
        };
    }

    private static void trim(ArrayDeque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() >= MINUTE) times.removeFirst();
    }

    /** Forgets what no window still needs, at most once a minute, so the Wire's memory stays bounded. */
    private void prune(long now) {
        if (lastPrune != Long.MIN_VALUE && now - lastPrune < MINUTE) return;
        lastPrune = now;
        lastSame.values().removeIf(t -> now - t >= LONGEST);
        heardSame.values().removeIf(t -> now - t >= LONGEST);
        for (Iterator<ArrayDeque<Long>> it = sentOn.values().iterator(); it.hasNext(); ) {
            var q = it.next();
            trim(q, now);
            if (q.isEmpty()) it.remove();
        }
        for (Iterator<ArrayDeque<Long>> it = heardBy.values().iterator(); it.hasNext(); ) {
            var q = it.next();
            trim(q, now);
            if (q.isEmpty()) it.remove();
        }
    }

    /** How many message identities and line counts it holds, for the bound's test. */
    int heldForTest() { return sentOn.size() + lastSame.size() + heardBy.size() + heardSame.size(); }
}
