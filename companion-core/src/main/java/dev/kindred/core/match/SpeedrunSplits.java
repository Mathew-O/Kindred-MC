package dev.kindred.core.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: the checkpoints of a speedrun race, from smelting iron to killing the Ender Dragon, each
 * marked by the game's own advancement so humans and AI players are timed alike. It records when each runner
 * reached each one and shows the time as a speedrun timer does.
 *
 * Technical: A speedrun's splits (M-03a): the points on the way to the dragon a runner's time is taken at,
 * each one a vanilla advancement, so the game itself says when a runner got there, the same for humans and
 * AIs. The time is the game's own: ticks since the start, twenty to a second, unaffected by how fast the
 * server runs them.
 */
public final class SpeedrunSplits {
    /** One point on the route, and the advancement that marks it. */
    public record Split(String id, String label, String advancement) { }

    /** Any%: iron, the Nether, blaze rods, the stronghold, the End and the dragon, the last one the finish. */
    public static final List<Split> ANY_PERCENT = List.of(
            new Split("iron", "Iron", "minecraft:story/smelt_iron"),
            new Split("nether", "Nether", "minecraft:story/enter_the_nether"),
            new Split("rods", "Blaze rods", "minecraft:nether/obtain_blaze_rod"),
            new Split("stronghold", "Stronghold", "minecraft:story/follow_ender_eye"),
            new Split("end", "The End", "minecraft:story/enter_the_end"),
            new Split("dragon", "Dragon", "minecraft:end/kill_dragon"));

    /** A split reached: by whom, and at what time since the start. */
    public record Reached(UUID runner, Split split, long ticks) {
        public boolean finish() { return split.equals(ANY_PERCENT.getLast()); }
    }

    private final long start;
    private final Map<UUID, Map<String, Long>> reached = new LinkedHashMap<>();

    public SpeedrunSplits(long startTick) { this.start = startTick; }

    public long start() { return start; }

    /** {@code runner} has {@code split}'s advancement at {@code tick}: the split, if it is the first time. */
    public Optional<Reached> reach(UUID runner, Split split, long tick) {
        var mine = reached.computeIfAbsent(runner, id -> new LinkedHashMap<>());
        if (mine.containsKey(split.id())) return Optional.empty();
        long ticks = Math.max(0, tick - start);
        mine.put(split.id(), ticks);
        return Optional.of(new Reached(runner, split, ticks));
    }

    /** The splits {@code runner} has reached, in the order reached. */
    public List<Reached> of(UUID runner) {
        var out = new ArrayList<Reached>();
        var mine = reached.getOrDefault(runner, Map.of());
        for (var entry : mine.entrySet()) {
            ANY_PERCENT.stream().filter(s -> s.id().equals(entry.getKey())).findFirst()
                    .ifPresent(split -> out.add(new Reached(runner, split, entry.getValue())));
        }
        return out;
    }

    /** The time as a speedrun timer shows it: minutes, seconds and hundredths, with hours once past the hour. */
    public static String time(long ticks) {
        long millis = Math.max(0, ticks) * 50;
        long hundredths = millis / 10 % 100, seconds = millis / 1000 % 60, minutes = millis / 60_000 % 60, hours = millis / 3_600_000;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d.%02d", hours, minutes, seconds, hundredths)
                : String.format(Locale.ROOT, "%d:%02d.%02d", minutes, seconds, hundredths);
    }
}
