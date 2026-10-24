package dev.kindred.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a run went for each player, read from the server's log: the measures of the review of 2026-10-24 (section 5)
 * that the log carries. What it reads:
 * <ul>
 *   <li>Each task's end: {@code <player> <skill> COMPLETED|BLOCKED|CANCELLED[: reason] [label]}.</li>
 *   <li>Each step of its work: {@code <player> goal at x y z: <goal>: ...}, {@code <player> build: <blueprint>: ...},
 *       {@code <player> ranch: ...}, {@code <player> field: ...}.</li>
 * </ul>
 * What it measures, per player:
 * <ul>
 *   <li><b>Failure memory.</b> The same failure (skill, reason and label, its target in the label) again within ten
 *       minutes of the last: the most times one failure came within ten minutes (target: at most 1 repeat).</li>
 *   <li><b>Goal continuity.</b> A task cut short by a hazard, a threat or a fall, and whether the work it was on
 *       (the goal, the build, the ranch, the field) is on again within five minutes (target: 95%).</li>
 *   <li><b>Commitment.</b> Changes of work per minute, leaving out the minute after an interruption (target: at most
 *       2).</li>
 *   <li><b>Recovery.</b> A stuck spell: the same task ending the same way eight times running. Recovered if a task
 *       of another label is completed after it (target: 90%).</li>
 *   <li><b>Walks in vain.</b> Walks that found no way, a minute, and the longest run of them with no task done between:
 *       one target after another from a place it cannot leave, which no measure of one target sees.</li>
 * </ul>
 * Run with the log's path: {@code ./gradlew :companion-testkit:runMetrics --args=<log>}.
 */
public final class RunMetrics {
    private static final Pattern STAMP = Pattern.compile("^\\[(\\d\\d):(\\d\\d):(\\d\\d)\\] \\[[^\\]]*\\] \\(Kindred\\) (.*)$");
    private static final Pattern OUTCOME = Pattern.compile("^(\\S+) (native_\\w+) (COMPLETED|BLOCKED|CANCELLED)(?:: (.*?))?(?: \\[(.*)\\])?$");
    private static final Pattern GOAL = Pattern.compile("^(\\S+) goal at -?\\d+ -?\\d+ -?\\d+: ([^:]+)(?::.*)?$");
    private static final Pattern WORK = Pattern.compile("^(\\S+) (build|ranch|field): (?:([^:]+):)?.*$");
    /** Reasons a task was cut short by the world, not by its own failing. */
    private static final List<String> INTERRUPTIONS = List.of("Hazard/threat detected", "A hostile mob is attacking", "Falling");

    static final int REPEAT_WINDOW = 600, RESUME_WINDOW = 300, SETTLE = 60, STUCK_RUN = 8;

    /** One player's measures over the run. */
    public static final class Player {
        final String name;
        int completed, blocked, cancelled;
        /** The most times one failure came again within ten minutes of the last, and which. */
        int worstRepeats;
        String worstFailure = "";
        int interruptions, resumed;
        int switches;
        long first = -1, last = -1;
        int stuckSpells, recovered;
        int walksInVain, longestVainRun;
        private int vainRun;

        // Working state.
        private final Map<String, Long> lastFailure = new HashMap<>();
        private final Map<String, Integer> repeats = new HashMap<>();
        private String work;
        private long settledAt = Long.MIN_VALUE;
        private final List<Interruption> open = new ArrayList<>();
        private String runKey;
        private int runLength;
        private boolean stuck;
        private String stuckLabel;

        Player(String name) { this.name = name; }

        /** Work changes per minute of the run. */
        public double switchesPerMinute() {
            double minutes = Math.max(1, last - first) / 60.0;
            return switches / minutes;
        }

        public double resumedShare() { return interruptions == 0 ? 1.0 : (double) resumed / interruptions; }

        public double recoveredShare() { return stuckSpells == 0 ? 1.0 : (double) recovered / stuckSpells; }

        public int worstRepeats() { return worstRepeats; }

        public int interruptions() { return interruptions; }

        public int resumed() { return resumed; }

        public int switches() { return switches; }

        public int stuckSpells() { return stuckSpells; }

        public int recovered() { return recovered; }

        public int walksInVain() { return walksInVain; }

        public int longestVainRun() { return longestVainRun; }

        /** Walks that found no way, a minute of the run. */
        public double walksInVainPerMinute() {
            return walksInVain / (Math.max(1, last - first) / 60.0);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s: %d tasks done, %d blocked, %d cut short; failure repeats within 10 min: worst %d%s;"
                            + " interruptions %d, work resumed %d (%.0f%%); work changes %.2f a minute; stuck %d, recovered %d (%.0f%%);"
                            + " walks in vain %d (%.1f a minute), at most %d running",
                    name, completed, blocked, cancelled, worstRepeats, worstFailure.isEmpty() ? "" : " (" + worstFailure + ")",
                    interruptions, resumed, 100 * resumedShare(), switchesPerMinute(), stuckSpells, recovered, 100 * recoveredShare(),
                    walksInVain, walksInVainPerMinute(), longestVainRun);
        }
    }

    private record Interruption(String work, long at) { }

    private final Map<String, Player> players = new LinkedHashMap<>();
    private long day, previous = -1;

    /** Reads one line of the log; anything not the runtime's is passed over. */
    public void read(String line) {
        Matcher stamp = STAMP.matcher(line);
        if (!stamp.matches()) return;
        long seconds = Integer.parseInt(stamp.group(1)) * 3600L + Integer.parseInt(stamp.group(2)) * 60L + Integer.parseInt(stamp.group(3));
        // A run past midnight: the clock goes back by most of a day.
        if (previous >= 0 && seconds + day < previous - 12 * 3600) day += 24 * 3600;
        long at = seconds + day;
        previous = at;
        String text = stamp.group(4);
        Matcher outcome = OUTCOME.matcher(text);
        if (outcome.matches()) {
            outcome(player(outcome.group(1), at), at, outcome.group(2), outcome.group(3),
                    outcome.group(4) == null ? "" : outcome.group(4), outcome.group(5) == null ? "" : outcome.group(5));
            return;
        }
        Matcher goal = GOAL.matcher(text);
        if (goal.matches()) {
            work(player(goal.group(1), at), at, "goal " + goal.group(2).trim());
            return;
        }
        Matcher work = WORK.matcher(text);
        if (work.matches()) {
            work(player(work.group(1), at), at, work.group(2) + (work.group(3) == null ? "" : " " + work.group(3).trim()));
        }
    }

    private Player player(String name, long at) {
        Player p = players.computeIfAbsent(name, Player::new);
        if (p.first < 0) p.first = at;
        p.last = at;
        return p;
    }

    private void outcome(Player p, long at, String skill, String state, String reason, String label) {
        switch (state) {
            case "COMPLETED" -> p.completed++;
            case "BLOCKED" -> p.blocked++;
            default -> p.cancelled++;
        }
        if (state.equals("BLOCKED") && skill.equals("native_goto") && (reason.startsWith("No walkable route") || reason.startsWith("Route to"))) {
            p.walksInVain++;
            p.longestVainRun = Math.max(p.longestVainRun, ++p.vainRun);
        } else if (state.equals("COMPLETED")) {
            p.vainRun = 0;
        }
        if (state.equals("BLOCKED")) {
            String key = skill + ": " + reason + " [" + label + "]";
            Long before = p.lastFailure.put(key, at);
            int count = before != null && at - before <= REPEAT_WINDOW ? p.repeats.merge(key, 1, Integer::sum) : 0;
            if (count == 0) p.repeats.put(key, 0);
            if (count > p.worstRepeats) {
                p.worstRepeats = count;
                p.worstFailure = key;
            }
        }
        if (state.equals("CANCELLED") && INTERRUPTIONS.stream().anyMatch(reason::startsWith)) {
            p.interruptions++;
            p.open.add(new Interruption(p.work, at));
            p.settledAt = at + SETTLE;
        }
        // A stuck spell: the same task ending the same way, over and over.
        String run = skill + " " + state + " " + reason + " [" + label + "]";
        if (run.equals(p.runKey)) {
            if (++p.runLength == STUCK_RUN && !p.stuck) {
                p.stuck = true;
                p.stuckSpells++;
                p.stuckLabel = label;
            }
        } else {
            p.runKey = run;
            p.runLength = 1;
        }
        if (p.stuck && state.equals("COMPLETED") && !label.equals(p.stuckLabel)) {
            p.stuck = false;
            p.recovered++;
        }
        expire(p, at);
    }

    private void work(Player p, long at, String work) {
        expire(p, at);
        if (p.work != null && !p.work.equals(work) && at >= p.settledAt) p.switches++;
        p.work = work;
        p.open.removeIf(i -> {
            boolean back = i.work() == null || i.work().equals(work);
            if (back) p.resumed++;
            return back;
        });
    }

    /** Interruptions whose work did not come back within the window are not waited on any longer. */
    private static void expire(Player p, long at) {
        p.open.removeIf(i -> at - i.at() > RESUME_WINDOW);
    }

    public Map<String, Player> players() { return players; }

    public static RunMetrics of(List<String> lines) {
        var metrics = new RunMetrics();
        lines.forEach(metrics::read);
        return metrics;
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: RunMetrics <server log> [more logs]");
            System.exit(2);
        }
        for (String file : args) {
            var metrics = of(Files.readAllLines(Path.of(file), StandardCharsets.UTF_8));
            System.out.println(file + ":");
            for (var p : metrics.players().values()) System.out.println("  " + p);
        }
    }
}
