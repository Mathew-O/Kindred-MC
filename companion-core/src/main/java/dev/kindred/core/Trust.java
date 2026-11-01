package dev.kindred.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: how wary one AI player is of each person, from what the game itself saw them do to it: a blow, a
 * walk it was asked on that ended in harm, its chest emptied while they were seen at it, asks for more than it hands
 * out in a day. Wariness fades a little each quiet game day, and a gift brings it down. A wary player holds back (it
 * refuses their asks, hands them nothing, keeps its distance); it never strikes back.
 *
 * Technical: E-49. Pure and bounded, per player: a score from 0 to {@link #MOST} for each person, separate from
 * friendship and tone, from events the adapter attributes; {@link #WARY} and above withholds. Each harmful event adds
 * its weight; each whole game day ({@link #DAY} ticks) since the last of them takes {@link #QUIET_DAY} away; a gift
 * takes {@link #GIFT} away at once. Tosses to one person are capped at {@link #TOSS_CAP} items a game day (the day as
 * the world's tick divided by {@link #DAY}), and none while wary; each ask past the cap counts as an event. The game
 * clock only moves forward here: a tick earlier than one seen counts as the latest seen.
 */
public final class Trust {
    /** Wariness at or above which the player withholds: asks refused, nothing tossed. */
    public static final int WARY = 50;
    /** The most wariness there is. */
    public static final int MOST = 100;
    /** Items tossed to one person in a game day, at most. */
    public static final int TOSS_CAP = 16;
    /** A game day in ticks. */
    public static final long DAY = 24_000;
    /** Wariness a quiet game day takes away. */
    public static final int QUIET_DAY = 10;
    /** Wariness a gift or a return takes away. */
    public static final int GIFT = 20;
    /** How long after a blow it keeps away from the one who struck it: a minute. */
    public static final long KEEP_AWAY = 1_200;

    /** What a person did, as the game attributes it, and how much wariness it adds. */
    public enum Event {
        /** Hurt it: a blow or an arrow, outside a match where the two may strike each other. */
        HURT(40, "hurt you"),
        /** It was harmed within ten seconds of arriving where they asked it to come. */
        LED_INTO_HARM(30, "led you into harm"),
        /** Something was gone from a chest of its own, and they were seen at it. */
        TOOK_FROM_CHEST(30, "took from your chest"),
        /** Asked it for a toss past the day's cap. */
        TOSS_PAST_CAP(10, "asked you for more than you hand out");

        public final int weight;
        /** What it is, said to the player's voice as "this person has ... lately". */
        public final String words;

        Event(int weight, String words) {
            this.weight = weight;
            this.words = words;
        }
    }

    /** One person's standing: the score as of {@code settledAt}, the last harm and what it was, the day's tosses. */
    private record Standing(int score, long settledAt, long quietSince, Event last, long lastHurt, long tossDay, int tossed) {
        static Standing fresh(long now) { return new Standing(0, now, now, null, Long.MIN_VALUE, Long.MIN_VALUE, 0); }

        /** Whole quiet days owed since it was settled: those since the last harm, less those already counted. */
        int owed(long now) {
            long counted = Math.max(0, settledAt - quietSince) / DAY;
            long all = Math.max(0, now - quietSince) / DAY;
            return (int) Math.min(MOST, all - counted);
        }

        int current(long now) { return Math.max(0, score - QUIET_DAY * owed(now)); }
    }

    private final Map<UUID, Standing> people = new HashMap<>();
    private long latest = Long.MIN_VALUE;

    private long clock(long tick) {
        latest = Math.max(latest, tick);
        return latest;
    }

    private Standing of(UUID person, long now) {
        return people.getOrDefault(person, Standing.fresh(now));
    }

    /** How wary it is of {@code person} at {@code tick}, 0 to {@link #MOST}. */
    public int wariness(UUID person, long tick) {
        Objects.requireNonNull(person);
        long now = clock(tick);
        Standing standing = people.get(person);
        return standing == null ? 0 : standing.current(now);
    }

    public boolean wary(UUID person, long tick) { return wariness(person, tick) >= WARY; }

    /** What {@code person} last did to it while it is still wary of them, for its voice; none otherwise. */
    public Optional<Event> why(UUID person, long tick) {
        Standing standing = people.get(Objects.requireNonNull(person));
        return standing == null || !wary(person, tick) ? Optional.empty() : Optional.ofNullable(standing.last());
    }

    /** Whether {@code person} hurt it within the last {@link #KEEP_AWAY} ticks. */
    public boolean struckLately(UUID person, long tick) {
        long now = clock(tick);
        Standing standing = people.get(Objects.requireNonNull(person));
        return standing != null && standing.lastHurt() != Long.MIN_VALUE && now - standing.lastHurt() <= KEEP_AWAY;
    }

    /** {@code person} did {@code event} at {@code tick}: its weight added, the quiet days counted from now. */
    public void note(UUID person, Event event, long tick) {
        Objects.requireNonNull(person);
        Objects.requireNonNull(event);
        long now = clock(tick);
        Standing was = of(person, now);
        int score = Math.min(MOST, was.current(now) + event.weight);
        people.put(person, new Standing(score, now, now, event, event == Event.HURT ? now : was.lastHurt(), was.tossDay(), was.tossed()));
    }

    /** A gift or a return from {@code person}: {@link #GIFT} less wariness at once; the quiet days go on counting. */
    public void gift(UUID person, long tick) {
        long now = clock(tick);
        Standing was = people.get(Objects.requireNonNull(person));
        if (was == null) return;
        int score = Math.max(0, was.current(now) - GIFT);
        people.put(person, new Standing(score, now, was.quietSince(), was.last(), was.lastHurt(), was.tossDay(), was.tossed()));
    }

    /** How many items it would still toss {@code person} today: none while wary, else what the cap leaves. */
    public int tossAllowance(UUID person, long tick) {
        long now = clock(tick);
        if (wary(person, now)) return 0;
        Standing standing = people.get(person);
        long day = Math.floorDiv(now, DAY);
        int tossed = standing == null || standing.tossDay() != day ? 0 : standing.tossed();
        return Math.max(0, TOSS_CAP - tossed);
    }

    /** It tossed {@code person} {@code count} items at {@code tick}: counted against today's cap. */
    public void tossed(UUID person, int count, long tick) {
        if (count <= 0) return;
        long now = clock(tick);
        Standing was = of(Objects.requireNonNull(person), now);
        long day = Math.floorDiv(now, DAY);
        int already = was.tossDay() == day ? was.tossed() : 0;
        people.put(person, new Standing(was.score(), was.settledAt(), was.quietSince(), was.last(), was.lastHurt(), day,
                Math.min(TOSS_CAP, already + count)));
    }

    /** People it holds any wariness of, or has tossed to: for its records and fixtures. */
    public int known() { return people.size(); }
}
