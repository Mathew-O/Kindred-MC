package dev.kindred.core.social;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: how often an AI player may speak first to each person, and on what. To each person, one line it was not
 * asked for every three minutes, or as that person asked (fewer, or more), and none while they are fighting, building
 * fast, waiting for a match to start or away from the keyboard, nor beyond earshot. Someone who answers earns a little
 * more; someone who never does gets less, and both drift back over a day. On top of that, each occasion has its own
 * limit: an offer of food to a person once an hour, asking for a thing it needs once an hour, a warning of danger at once
 * and then not for thirty seconds.
 *
 * Technical: E-53's attention budget, pure, one per AI player; the adapter says who is where and in what state, and
 * which occasion it would speak on. Times are milliseconds of the adapter's clock. Bounded: 256 people and 256 occasion
 * keys, the least lately used dropped.
 */
public final class Initiative {
    /** One unasked line in this long to each person, by default. */
    public static final long BASE_MILLIS = 180_000;
    public static final long HOUR = 3_600_000;
    public static final long WARNING_MILLIS = 30_000;
    /** How far what someone has earned (or lost) drifts back to nothing: in a day. */
    public static final long DAY = 86_400_000;
    /** How long after a line a reply counts as answering it. */
    public static final long ANSWER_WINDOW = 60_000;

    /** What a person is doing now, as the adapter sees it. */
    public record State(boolean inEarshot, boolean fighting, boolean placingFast, boolean inCountdown, boolean away) {
        public static final State FREE = new State(true, false, false, false, false);

        /** Why nothing unasked may be said to them now, or null. */
        public String busy() {
            if (!inEarshot) return "out of earshot";
            if (fighting) return "fighting";
            if (placingFast) return "building";
            if (inCountdown) return "in a countdown";
            if (away) return "away";
            return null;
        }
    }

    /** What a line is said on. */
    public enum Occasion { OFFER, REQUEST, WARNING, BANTER, PROPOSAL }

    private static final class Person {
        long lastSpoken = Long.MIN_VALUE;
        boolean answeredLast = true;
        double earned;
        long earnedAt;
    }

    private final Map<UUID, Person> people = new LinkedHashMap<>();
    private final Map<String, Long> occasions = new HashMap<>();

    /**
     * Whether the player may say a line on {@code occasion} ({@code key} names it: the person for an offer, the need for a
     * request) to {@code person} now: their state allows it, the occasion's own limit has passed, and, but for a warning,
     * their budget has too.
     */
    public boolean may(UUID person, State state, Occasion occasion, String key, ListenerPreferences preferences, long now) {
        Objects.requireNonNull(person);
        Objects.requireNonNull(state);
        Objects.requireNonNull(occasion);
        if (!state.inEarshot() || state.inCountdown() || state.away()) return false;
        // A warning of danger is for someone fighting too; nothing else is.
        if (occasion != Occasion.WARNING && (state.fighting() || state.placingFast())) return false;
        Long last = occasions.get(occasionKey(occasion, key));
        if (last != null && now - last < limit(occasion)) return false;
        if (occasion == Occasion.WARNING) return true;
        Person p = people.get(person);
        return p == null || p.lastSpoken == Long.MIN_VALUE || now >= p.lastSpoken + interval(p, preferences, now);
    }

    /** A line was said on {@code occasion} to {@code person}: their budget and the occasion's limit start again. */
    public void said(UUID person, Occasion occasion, String key, ListenerPreferences preferences, long now) {
        Person p = person(person);
        if (occasion != Occasion.WARNING) {
            if (p.lastSpoken != Long.MIN_VALUE && !p.answeredLast) lose(p, now);
            p.lastSpoken = now;
            p.answeredLast = false;
        }
        occasions.remove(occasionKey(occasion, key));
        occasions.put(occasionKey(occasion, key), now);
        while (occasions.size() > 256) occasions.remove(occasions.keySet().iterator().next());
    }

    /** The person spoke to the player: if within a minute of a line it said to them unasked, they answered it. */
    public void answered(UUID person, long now) {
        Person p = people.get(person);
        if (p == null || p.lastSpoken == Long.MIN_VALUE || p.answeredLast || now - p.lastSpoken > ANSWER_WINDOW) return;
        p.answeredLast = true;
        p.earned = Math.min(1, earned(p, now) + 0.25);
        p.earnedAt = now;
    }

    /** How long, from a line said to them, before the next unasked one: their preference, then what they have earned. */
    public long interval(UUID person, ListenerPreferences preferences, long now) {
        return interval(person(person), preferences, now);
    }

    private long interval(Person p, ListenerPreferences preferences, long now) {
        long base = preferences == null ? BASE_MILLIS : preferences.spacing(BASE_MILLIS);
        // Earned from -1 (never answers) to 1 (always does): half as long again at worst, half as long at best.
        return Math.round(base * (1 - 0.5 * earned(p, now)));
    }

    private static void lose(Person p, long now) {
        p.earned = Math.max(-1, earned(p, now) - 0.25);
        p.earnedAt = now;
    }

    /** What a person has earned, drifted back toward nothing over a day. */
    private static double earned(Person p, long now) {
        if (p.earned == 0) return 0;
        double left = 1 - Math.min(1, Math.max(0, now - p.earnedAt) / (double) DAY);
        return p.earned * left;
    }

    /** What this person has earned now, from -1 to 1. */
    public double earned(UUID person, long now) {
        Person p = people.get(person);
        return p == null ? 0 : earned(p, now);
    }

    private Person person(UUID id) {
        Person p = people.get(id);
        if (p == null) {
            p = new Person();
            people.put(id, p);
            while (people.size() > 256) people.remove(people.keySet().iterator().next());
        }
        return p;
    }

    private static long limit(Occasion occasion) {
        return switch (occasion) {
            case OFFER, REQUEST -> HOUR;
            case WARNING -> WARNING_MILLIS;
            case PROPOSAL -> 30 * 60_000L;
            case BANTER -> 0;
        };
    }

    private static String occasionKey(Occasion occasion, String key) {
        return occasion + ":" + (key == null ? "" : key);
    }
}
