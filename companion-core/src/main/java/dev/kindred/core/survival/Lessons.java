package dev.kindred.core.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * In plain terms: what a player learns from its own play, with no adviser. Twice dead hungry, it carries more food;
 * three lives lost below ground in a day, it digs its own way for ore; a fall that kills it twice, and it keeps the
 * water bucket to hand. A habit it learned so and has gone three game days without needing is dropped again.
 *
 * Technical: E-46. Pure rules over the player's own record in a world: its deaths, each with the game time, its height,
 * whether it was night, under the sky and in the overworld, its food, how it died, and whether a mob in its own tunnel
 * behind it did it; and its strandings, a pickaxe broken below ground with none spare. Each rule names its trigger and
 * window, the habit it teaches and the note it writes. The notes go into the player's memory as the strategist's do,
 * and the habits through the same path. A habit the player already keeps is not taught again. One that lessons taught is
 * dropped after three game days without its trigger. One retired, by a lesson or by the strategist, is not taught again
 * from what came before its retirement. Nothing here reads the world.
 */
public final class Lessons {
    /** A game day, in ticks. */
    public static final long DAY = 24_000;
    /** A habit lessons taught is dropped after this long without its trigger. */
    public static final long LAPSE = 3 * DAY;
    /** A death with its food at this or below is a hungry one. */
    public static final int HUNGRY = 6;
    /** A death below this height in the overworld is one below ground. */
    public static final int DEEP = 40;

    /** How a death came. */
    public enum How { MOB, FALL, OTHER }

    /**
     * A death as the lessons read it: the game time, the height, at night, under the sky, in the overworld, the food it
     * had, how it came, and whether a mob in its own tunnel behind it struck the blow.
     */
    public record Death(long time, int y, boolean night, boolean underSky, boolean overworld, int food, How how, boolean ownTunnel) {
        public Death {
            Objects.requireNonNull(how);
        }
    }

    /** A pickaxe that broke below ground, none spare, at this game time. */
    public record Stranded(long time) { }

    /** What the record teaches now: a habit learned with its note, or, retired, one dropped with its note. */
    public record Lesson(PlanPreferences.Habit habit, String note, boolean retire) {
        public Lesson {
            Objects.requireNonNull(habit);
            Objects.requireNonNull(note);
        }
    }

    /** A rule: what counts toward it, how many within how long, the habit it teaches, its note, and the note it is dropped with. */
    record Rule(PlanPreferences.Habit habit, int count, long window, Predicate<Death> deaths, boolean strandings, String note, String dropped) { }

    static final List<Rule> RULES = List.of(
            new Rule(PlanPreferences.Habit.MORE_FOOD, 2, 2 * DAY, d -> d.food() <= HUNGRY, false,
                    "Twice dead hungry; carry more food.", "Three days without dying hungry; dropping the habit of carrying more food."),
            new Rule(PlanPreferences.Habit.SHELTER_EARLY, 2, 2 * DAY, d -> d.night() && d.underSky() && d.overworld(), false,
                    "Night on the surface has cost two lives; dig in at dusk.",
                    "Three days without a night death on the surface; dropping the habit of digging in at dusk."),
            new Rule(PlanPreferences.Habit.NO_CAVES, 3, DAY, d -> d.overworld() && d.y() < DEEP, false,
                    "The caves took three lives today; dig for ore instead.",
                    "Three days without a death below ground; dropping the habit of keeping out of caves."),
            new Rule(PlanPreferences.Habit.SPARE_PICKAXE, 1, DAY, d -> false, true,
                    "Stranded without a pickaxe; pack a spare.", "Three days without a broken last pickaxe; dropping the habit of a spare pickaxe."),
            new Rule(PlanPreferences.Habit.TORCH_AS_YOU_GO, 1, DAY, Death::ownTunnel, false,
                    "Something spawned in my own tunnel; torches as I go.",
                    "Three days without a mob in my own tunnel; dropping the habit of torches as I go."),
            new Rule(PlanPreferences.Habit.BUCKET_ON_HOTBAR, 2, 2 * DAY, d -> d.how() == How.FALL, false,
                    "Falls have cost two lives; keep the bucket to hand.", "Three days without a fall death; dropping the habit of the bucket to hand."));

    private Lessons() { }

    /**
     * What the record teaches at game time {@code now}. {@code kept} is every habit the player keeps, from lessons or
     * the strategist; {@code learned} those lessons taught, with when; {@code retired} those dropped, with when. A rule
     * whose habit is kept teaches nothing; one counts only what came after its habit's retirement. A habit in
     * {@code learned} still kept, whose trigger has not come for {@link #LAPSE} since it was learned or last came, is
     * retired.
     */
    public static List<Lesson> review(List<Death> deaths, List<Stranded> stranded, Set<PlanPreferences.Habit> kept,
                                      Map<PlanPreferences.Habit, Long> learned, Map<PlanPreferences.Habit, Long> retired, long now) {
        var lessons = new ArrayList<Lesson>();
        for (Rule rule : RULES) {
            long since = retired.getOrDefault(rule.habit(), Long.MIN_VALUE);
            if (kept.contains(rule.habit())) {
                Long at = learned.get(rule.habit());
                if (at == null) continue; // The strategist's: it is the one to retire it.
                long last = Math.max(at, latest(rule, deaths, stranded, since));
                if (now - last >= LAPSE) {
                    lessons.add(new Lesson(rule.habit(), rule.dropped(), true));
                }
                continue;
            }
            if (fires(rule, deaths, stranded, since, now)) lessons.add(new Lesson(rule.habit(), rule.note(), false));
        }
        return lessons;
    }

    /**
     * Whether the rule's trigger has come its count of times within its window of each other, after {@code after}, the
     * latest of them within the window before {@code now}.
     */
    private static boolean fires(Rule rule, List<Death> deaths, List<Stranded> stranded, long after, long now) {
        var times = new ArrayList<Long>();
        for (Death death : deaths) if (death.time() > after && death.time() <= now && rule.deaths().test(death)) times.add(death.time());
        if (rule.strandings()) for (Stranded s : stranded) if (s.time() > after && s.time() <= now) times.add(s.time());
        if (times.isEmpty()) return false;
        long last = java.util.Collections.max(times);
        if (last <= now - rule.window()) return false;
        return times.stream().filter(time -> time > last - rule.window()).count() >= rule.count();
    }

    /** When the rule's trigger last came after {@code after}, or the earliest time there is. */
    private static long latest(Rule rule, List<Death> deaths, List<Stranded> stranded, long after) {
        long last = Long.MIN_VALUE;
        for (Death death : deaths) if (death.time() > after && rule.deaths().test(death)) last = Math.max(last, death.time());
        if (rule.strandings()) for (Stranded s : stranded) if (s.time() > after) last = Math.max(last, s.time());
        return last;
    }

    /** The note a rule writes for the habit it teaches, or none for a habit no lesson teaches. */
    public static java.util.Optional<String> note(PlanPreferences.Habit habit) {
        return RULES.stream().filter(rule -> rule.habit() == habit).map(Rule::note).findFirst();
    }
}
