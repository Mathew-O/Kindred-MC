package dev.kindred.core.social;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: two AI players talking when they meet after a while apart: one asks after what the other was last doing
 * or needing, the other answers from its own record of what it is doing and needs, and the first says a last word; a
 * playful one may first tease the other with something they went through together. Nothing is said twice within half an
 * hour, and the whole server's AI players say at most a few such lines a minute.
 *
 * Technical: E-51, pure. {@link #plan} chooses the topic (news from each side's ledger of the other, ranked by novelty to
 * the one asked and by sociability; nothing whose key was spoken in the last thirty minutes) and writes two or three turns
 * from templates in each speaker's manner. Every reply is written from its speaker's own record ({@link Side}), never by
 * reading the other's words. {@link Chatter} is the server-wide budget: lines a minute (default four, {@code
 * KINDRED_CHATTER}), a pair at most once in two minutes.
 */
public final class Conversation {
    private Conversation() { }

    /** Apart this long, two players meeting have something to say. */
    public static final long APART_MILLIS = 600_000;
    /** A topic is not spoken of again for this long. */
    public static final long DEDUPE_MILLIS = 1_800_000;

    /**
     * One side as it is to itself: who it is, how sociable, how it sounds, what it is doing and needs and could spare (its
     * own record), and what it knows of the other from its ledger (their doing and needs as the team told it, and the last
     * thing they went through together).
     */
    public record Side(UUID id, String name, int sociability, Expression sounds, String doing, List<String> needs, List<String> spare,
                       String knownDoing, String knownNeeds, String lastEpisode) {
        public Side {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            sounds = sounds == null ? Expression.PLAIN : sounds;
            doing = doing == null ? "" : doing;
            needs = List.copyOf(needs == null ? List.of() : needs);
            spare = List.copyOf(spare == null ? List.of() : spare);
            knownDoing = knownDoing == null ? "" : knownDoing;
            knownNeeds = knownNeeds == null ? "" : knownNeeds;
            lastEpisode = lastEpisode == null ? "" : lastEpisode;
        }
    }

    /** One line: who says it to whom, its words, its plain variant, and whether it teases (E-18a's tone). */
    public record Turn(UUID speaker, UUID listener, String text, String plain, boolean tease) {
        public Turn {
            Objects.requireNonNull(speaker);
            Objects.requireNonNull(text);
            plain = plain == null ? text : plain;
        }

        /** The words for these hearers: the plain ones if any asked for no teasing or no jokes. */
        public String textFor(ListenerPreferences heard) {
            return tease && heard != null && (heard.noTeasing() || heard.noJokes()) ? plain : text;
        }
    }

    /** What the two talk about, and the key that keeps it from being said again for half an hour. */
    public record Topic(String key, Kind kind) {
        public enum Kind { DOING, NEEDS, EPISODE, HELLO }
    }

    /** The talk planned: its topic and its turns, in order. */
    public record Plan(Topic topic, List<Turn> turns) { }

    /**
     * The talk between {@code a} (who speaks first) and {@code b}, or empty when there is nothing new between them; {@code
     * spoken} holds when each topic key was last spoken.
     */
    public static Optional<Plan> plan(Side a, Side b, Map<String, Long> spoken, long now) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        var topics = new ArrayList<Topic>();
        String pair = a.id().compareTo(b.id()) < 0 ? a.id() + ":" + b.id() : b.id() + ":" + a.id();
        if (a.sounds() == Expression.PLAYFUL && !a.lastEpisode().isEmpty()) topics.add(new Topic(pair + ":episode:" + a.lastEpisode(), Topic.Kind.EPISODE));
        if (!b.doing().isEmpty()) topics.add(new Topic(b.id() + ":doing:" + b.doing(), Topic.Kind.DOING));
        if (!b.needs().isEmpty()) topics.add(new Topic(b.id() + ":needs:" + String.join(",", b.needs()), Topic.Kind.NEEDS));
        topics.add(new Topic(pair + ":hello", Topic.Kind.HELLO));
        // New to the one asked first: what a knows of b differs from b's own record; then by kind; never what was said lately.
        Comparator<Topic> rank = Comparator.comparingInt(t -> novelty(t, a, b));
        Topic topic = topics.stream().filter(t -> spoken == null || !recent(spoken.get(t.key()), now))
                .min(rank.thenComparingInt(t -> t.kind().ordinal())).orElse(null);
        if (topic == null) return Optional.empty();
        var turns = new ArrayList<Turn>();
        switch (topic.kind()) {
            case EPISODE -> {
                String plain = "Hi, " + b.name() + ".";
                turns.add(new Turn(a.id(), b.id(), "Hi, " + b.name() + ". After " + a.lastEpisode() + ", I'm keeping an eye on you.", plain, true));
                turns.add(new Turn(b.id(), a.id(), answerDoing(b), null, false));
            }
            case DOING -> {
                turns.add(new Turn(a.id(), b.id(), ask(a, b), null, false));
                turns.add(new Turn(b.id(), a.id(), answerDoing(b), null, false));
                if (!b.needs().isEmpty()) turns.add(new Turn(a.id(), b.id(), offerOrWish(a, b), null, false));
            }
            case NEEDS -> {
                turns.add(new Turn(a.id(), b.id(), "Still after " + b.needs().getFirst() + ", " + b.name() + "?", null, false));
                turns.add(new Turn(b.id(), a.id(), "Yes, " + String.join(" and ", b.needs()) + ".", null, false));
                turns.add(new Turn(a.id(), b.id(), offerOrWish(a, b), null, false));
            }
            case HELLO -> {
                turns.add(new Turn(a.id(), b.id(), hello(a, b), null, false));
                turns.add(new Turn(b.id(), a.id(), "Hi, " + a.name() + "." + (b.doing().isEmpty() ? "" : " Just " + shortDoing(b.doing()) + "."), null, false));
            }
        }
        return Optional.of(new Plan(topic, List.copyOf(turns)));
    }

    /** How new a topic is to {@code a}: 0 when its record of {@code b} is out of date on it, 1 otherwise; a playful callback first. */
    private static int novelty(Topic t, Side a, Side b) {
        return switch (t.kind()) {
            case DOING -> a.knownDoing().equals(b.doing()) ? 1 : 0;
            case NEEDS -> a.knownNeeds().equals(String.join(", ", b.needs())) ? 1 : 0;
            case EPISODE -> -1;
            case HELLO -> 2;
        };
    }

    private static boolean recent(Long at, long now) {
        return at != null && now - at < DEDUPE_MILLIS;
    }

    private static String ask(Side a, Side b) {
        if (!a.knownDoing().isEmpty() && !a.knownDoing().equals(b.doing())) return "How's it going, " + b.name() + "? Last I heard you were " + shortDoing(a.knownDoing()) + ".";
        return switch (a.sounds()) {
            case PLAIN -> "How's it going, " + b.name() + "?";
            case DRY -> b.name() + ". Busy?";
            case PLAYFUL -> "There you are, " + b.name() + "! What are you up to?";
        };
    }

    /** {@code b}'s answer, written from its own record: what it is doing, and what it needs. */
    static String answerDoing(Side b) {
        String doing = b.doing().isEmpty() ? "Nothing much right now." : "I'm " + shortDoing(b.doing()) + ".";
        return b.needs().isEmpty() ? doing : doing + " I could use " + b.needs().getFirst() + ".";
    }

    /** {@code a}'s last word on what {@code b} needs: what it has spare of it, or a wish. */
    private static String offerOrWish(Side a, Side b) {
        String need = b.needs().getFirst();
        String word = need.replaceFirst("^\\d+\\s+", "");
        for (String spare : a.spare()) if (spare.contains(word)) return "I've got some " + word + " spare if you want it.";
        return switch (a.sounds()) {
            case PLAIN -> "Good luck finding " + word + ".";
            case DRY -> "Good luck with that.";
            case PLAYFUL -> "I'll keep an eye out for " + word + " for you.";
        };
    }

    private static String hello(Side a, Side b) {
        return switch (a.sounds()) {
            case PLAIN -> "Hi, " + b.name() + ". Good to see you.";
            case DRY -> b.name() + ". Hello.";
            case PLAYFUL -> "Hey, " + b.name() + "! Long time no see.";
        };
    }

    static String shortDoing(String doing) {
        String d = doing.strip();
        if (d.startsWith("working on ")) {
            int colon = d.indexOf(':');
            d = colon > 0 ? d.substring(0, colon) : d;
        }
        return d.length() > 60 ? d.substring(0, 60) : d;
    }

    /**
     * The server-wide budget for lines between AI players: at most {@code perMinute} lines in any minute (none under {@code
     * quiet}), a pair at most once in two minutes.
     */
    public static final class Chatter {
        public static final int DEFAULT_PER_MINUTE = 4;
        public static final long PAIR_MILLIS = 120_000;
        private final int perMinute;
        private final ArrayDeque<Long> said = new ArrayDeque<>();
        private final Map<String, Long> pairs = new HashMap<>();

        public Chatter(int perMinute) {
            if (perMinute < 0) throw new IllegalArgumentException("Negative chatter");
            this.perMinute = perMinute;
        }

        /** The budget from {@code KINDRED_CHATTER}: a number of lines a minute, or {@code quiet} for none; four when unset or unreadable. */
        public static Chatter of(String setting) {
            if (setting == null || setting.isBlank()) return new Chatter(DEFAULT_PER_MINUTE);
            String s = setting.strip().toLowerCase(java.util.Locale.ROOT);
            if (s.equals("quiet") || s.equals("off")) return new Chatter(0);
            try {
                return new Chatter(Math.max(0, Math.min(60, Integer.parseInt(s))));
            } catch (NumberFormatException unreadable) {
                return new Chatter(DEFAULT_PER_MINUTE);
            }
        }

        public int perMinute() { return perMinute; }

        /** Whether {@code lines} more may be said by this pair now. */
        public boolean may(UUID a, UUID b, int lines, long now) {
            prune(now);
            Long last = pairs.get(pair(a, b));
            if (last != null && now - last < PAIR_MILLIS) return false;
            return said.size() + lines <= perMinute;
        }

        /** Room for one more line in the minute (a talk under way says its next turn only if there is). */
        public boolean mayLine(long now) {
            prune(now);
            return said.size() < perMinute;
        }

        /** A talk between this pair begins. */
        public void began(UUID a, UUID b, long now) {
            pairs.put(pair(a, b), now);
            while (pairs.size() > 256) pairs.remove(pairs.keySet().iterator().next());
        }

        /** A line was said. */
        public void line(long now) {
            said.addLast(now);
        }

        private void prune(long now) {
            while (!said.isEmpty() && now - said.peekFirst() >= 60_000) said.removeFirst();
        }

        private static String pair(UUID a, UUID b) {
            return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
        }
    }

    /** The keys a plan's topic leaves spoken, at {@code now}. */
    public static void spoken(Plan plan, Map<String, Long> spoken, long now) {
        spoken.put(plan.topic().key(), now);
        if (spoken.size() > 512) spoken.keySet().removeIf(key -> now - spoken.get(key) >= DEDUPE_MILLIS);
    }
}
