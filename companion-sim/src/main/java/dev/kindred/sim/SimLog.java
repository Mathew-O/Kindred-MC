package dev.kindred.sim;

/**
 * In plain terms: the simulator's log, written as the server writes its own, so that the same scorer reads both.
 *
 * Technical: F-28. Each line carries the game time as its clock ({@code [hh:mm:ss]}, twenty ticks a second) and the
 * server's prefix with the Kindred logger, as {@code RunMetrics} reads a server's log; its lines are the runtime's own
 * forms: a goal's step ({@code <name> goal at x y z: <stage>: <step>}), a task's end ({@code <name> <skill> <state>:
 * <why> [<label>]}) and a death ({@code <name> died at x, y, z: <message>}).
 */
final class SimLog {
    private final String name;
    private final StringBuilder text = new StringBuilder();

    SimLog(String name) { this.name = name; }

    String name() { return name; }

    void line(long tick, String line) {
        long seconds = tick / 20;
        text.append(String.format(java.util.Locale.ROOT, "[%02d:%02d:%02d] [Server thread/INFO] (Kindred) ", (seconds / 3600) % 24, (seconds / 60) % 60, seconds % 60))
                .append(line).append('\n');
    }

    String text() { return text.toString(); }
}
