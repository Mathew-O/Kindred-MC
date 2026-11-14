package dev.kindred.core.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In plain terms: what an unattended evaluation run saw each starter do, written out when the run ends: when it first
 * reached each stage of the way to the dragon, every death with what it was doing and where, every time it got stuck,
 * and the tokens its optional model players used.
 *
 * Technical: F-26's {@code eval-result.json}. Filled on the server thread from the same moments the log lines come
 * from; each stage keeps the first tick it was reached. Deaths and stuck spells are bounded (the first 500 of each
 * per player are kept, the rest counted), so a run that loops cannot grow the file without end.
 */
public final class EvalRecord {
    /** The stages of the way to the dragon, in order, by the names the file uses. */
    public static final List<String> STAGES = List.of("wooden_tool", "stone_tools", "iron_ingot", "iron_pickaxe", "diamond",
            "obsidian", "portal_lit", "nether_entered", "blaze_rods", "eyes", "stronghold", "end", "dragon");
    static final int KEPT = 500;

    /** A death as the game records it, with what the player was about. */
    public record Death(long tick, String cause, int x, int y, int z, int light, long timeOfDay, String dimension,
                        int hunger, boolean underSky, String stage, String stance, String habits, String task) { }

    /** A stuck spell: the campaign's own detector gave the spot up and tried elsewhere. */
    public record Stuck(long tick, int x, int y, int z, int attempt) { }

    /** A rescue (F-29): when, and the line the log gives it, {@code rescue: <steps> -> unstuck|failed <why>|refused <why>}. */
    public record Rescue(long tick, String line) { }

    private static final class Player {
        final Map<String, Long> stages = new LinkedHashMap<>();
        final List<Death> deaths = new ArrayList<>();
        final List<Stuck> stuck = new ArrayList<>();
        final List<Rescue> rescues = new ArrayList<>();
        int deathsDropped, stuckDropped, rescuesDropped;
        long tokens;
        /** Chunks that came into its loaded area, and chunks held times ticks held (E-70a). */
        long chunkLoads, chunkTicks;
        /** Bridge blocks placed in its bridge runs, and the ticks those runs took (E-71a). */
        long bridgeBlocks, bridgeTicks;
    }

    private final EvalScenario scenario;
    private final Map<String, Player> players = new LinkedHashMap<>();

    public EvalRecord(EvalScenario scenario) {
        this.scenario = scenario;
        for (String name : scenario.everyone()) players.put(name, new Player());
    }

    public EvalScenario scenario() { return scenario; }

    /** The first time {@code player} reached {@code stage}; later ones are not news. */
    public void reached(String player, String stage, long tick) {
        if (!STAGES.contains(stage)) throw new IllegalArgumentException("Unknown stage " + stage);
        var p = players.get(player);
        if (p != null) p.stages.putIfAbsent(stage, tick);
    }

    public boolean hasReached(String player, String stage) {
        var p = players.get(player);
        return p != null && p.stages.containsKey(stage);
    }

    public void died(String player, Death death) {
        var p = players.get(player);
        if (p == null) return;
        if (p.deaths.size() < KEPT) p.deaths.add(death); else p.deathsDropped++;
    }

    public void stuck(String player, Stuck stuck) {
        var p = players.get(player);
        if (p == null) return;
        if (p.stuck.size() < KEPT) p.stuck.add(stuck); else p.stuckDropped++;
    }

    public void rescued(String player, Rescue rescue) {
        var p = players.get(player);
        if (p == null) return;
        if (p.rescues.size() < KEPT) p.rescues.add(rescue); else p.rescuesDropped++;
    }

    /**
     * Chunks that came into {@code player}'s loaded area since the last look, and the chunks it held times the ticks since
     * then (E-70a's measure: a player's chunks are most of what it costs the server).
     */
    public void chunks(String player, long loads, long chunkTicks) {
        var p = players.get(player);
        if (p == null) return;
        p.chunkLoads += loads;
        p.chunkTicks += chunkTicks;
    }

    /**
     * A bridge run of {@code player}'s ended, with {@code blocks} placed in {@code ticks} (E-71a's measure: bridge blocks
     * per second, over every run whether it reached its end or stopped short).
     */
    public void bridged(String player, long blocks, long ticks) {
        var p = players.get(player);
        if (p == null || blocks < 0 || ticks < 0) return;
        p.bridgeBlocks += blocks;
        p.bridgeTicks += ticks;
    }

    /** The Overworld's days that passed over the run (a night slept through passes in moments); negative until set. */
    private double days = -1;

    /** The Overworld's days that passed over the run: its time of day's advance, over 24,000. */
    public void days(double days) { this.days = days; }

    /** The tokens its model players have used so far: the latest total replaces the last. */
    public void tokens(String player, long tokens) {
        var p = players.get(player);
        if (p != null) p.tokens = Math.max(p.tokens, tokens);
    }

    /**
     * The server's load over the run: its mean and worst tick in milliseconds, when the worst fell (ticks into the run),
     * how many ticks took over 50 and over 200 ms, and its heap at most in megabytes; null until set.
     */
    private String server;

    /** The server's switches for free travel and free items over the run (mod-support decision 6); allowed at any point is allowed. */
    private boolean teleport, freeItems;

    /** Records the server's switches for free travel and free items; once allowed in a run, the run stays not earned. */
    public void policy(boolean teleport, boolean freeItems) {
        this.teleport |= teleport;
        this.freeItems |= freeItems;
    }

    /**
     * Whether the run is earned progress: its scenario earned (nothing handed out or kept) and neither free travel nor free
     * items allowed on the server at any point. A teleport or a free item never counts as earned.
     */
    public boolean earned() { return scenario.earned() && !teleport && !freeItems; }

    /** The server's load over the run, as the file will hold it (the measurement a larger run is qualified by). */
    public void server(double tickMsMean, double tickMsWorst, long worstAtTick, int ticksOver50ms, int ticksOver200ms, long heapMbMax, int samples) {
        server = String.format(java.util.Locale.ROOT, ",\n  \"server\": {\"tickMsMean\": %.2f, \"tickMsWorst\": %.2f, \"tickMsWorstAt\": %d, "
                        + "\"ticksOver50ms\": %d, \"ticksOver200ms\": %d, \"heapMbMax\": %d, \"samples\": %d}",
                tickMsMean, tickMsWorst, worstAtTick, ticksOver50ms, ticksOver200ms, heapMbMax, samples);
    }

    /** A faction world's audit (M-06i), written into the file; null in any other run. */
    private String factions;

    /**
     * A faction world's audit against its locked rules, as the file will hold it: what the World Record holds, every rule
     * it shows broken, and why it could not be run if it could not ({@code note}, else empty).
     */
    public void factions(java.util.Map<String, Integer> counts, java.util.List<String> violations, String note) {
        var out = new StringBuilder(",\n  \"factions\": {\"audited\": ").append(note.isEmpty()).append(", \"note\": ").append(Json.string(note))
                .append(", \"counts\": {");
        boolean first = true;
        for (var c : counts.entrySet()) {
            out.append(first ? "" : ", ").append(Json.string(c.getKey())).append(": ").append(c.getValue());
            first = false;
        }
        out.append("}, \"violations\": [");
        first = true;
        for (String v : violations) {
            out.append(first ? "" : ", ").append(Json.string(v));
            first = false;
        }
        factions = out.append("]}").toString();
    }

    /** The item-source audit (M-06k), written into the file; null when none ran. */
    private String items;

    /**
     * The item-source audit, as the file will hold it: the rises in what the players held, how many a source paid for and
     * by which, the drops picked up by where they came from, the credits that lapsed unused, every rise no source paid
     * for, and what a snapshot of the players and a drop's tagging cost.
     */
    public void items(ItemSourceLedger.Report report, java.util.Map<String, Long> origins, long untaggedPickups, long forgottenDrops,
                      double snapshotMicrosMean, double snapshotMicrosMax, double tagMicrosMean, double tagMicrosMax) {
        var out = new StringBuilder(",\n  \"items\": {\"windows\": ").append(report.windows()).append(", \"gained\": ").append(report.gained())
                .append(", \"paid\": ").append(report.paid()).append(", \"lapsed\": ").append(report.lapsed())
                .append(", \"untaggedPickups\": ").append(untaggedPickups).append(", \"forgottenDrops\": ").append(forgottenDrops)
                .append(String.format(java.util.Locale.ROOT, ", \"snapshotMicrosMean\": %.1f, \"snapshotMicrosMax\": %.1f, \"tagMicrosMean\": %.1f, \"tagMicrosMax\": %.1f",
                        snapshotMicrosMean, snapshotMicrosMax, tagMicrosMean, tagMicrosMax))
                .append(", \"bySource\": {");
        boolean first = true;
        for (var e : report.bySource().entrySet()) {
            out.append(first ? "" : ", ").append(Json.string(e.getKey())).append(": ").append(e.getValue());
            first = false;
        }
        out.append("}, \"dropsByOrigin\": {");
        first = true;
        for (var e : origins.entrySet()) {
            out.append(first ? "" : ", ").append(Json.string(e.getKey())).append(": ").append(e.getValue());
            first = false;
        }
        out.append("}, \"violationCount\": ").append(report.violationCount()).append(", \"violations\": [");
        first = true;
        for (String v : report.violations()) {
            out.append(first ? "" : ", ").append(Json.string(v));
            first = false;
        }
        items = out.append("]}").toString();
    }

    public int deaths(String player) {
        var p = players.get(player);
        return p == null ? 0 : p.deaths.size() + p.deathsDropped;
    }

    /**
     * The file: the scenario as given and whether it is earned or staged, the build and seed it ran on, the ticks it
     * ran, and per player its stages, deaths, stuck spells and tokens. {@code complete} is false for a run that ended
     * before its ticks (the server stopped early).
     */
    public String toJson(String build, long seed, long ticksRun, boolean complete) {
        var out = new StringBuilder(4096);
        out.append("{\n  \"scenario\": ").append(Json.string(scenario.asText()))
                .append(",\n  \"kind\": ").append(Json.string(earned() ? "earned" : "staged"))
                .append(",\n  \"policy\": {\"teleport\": ").append(teleport).append(", \"freeItems\": ").append(freeItems).append('}')
                .append(",\n  \"build\": ").append(Json.string(build))
                .append(",\n  \"seed\": ").append(seed)
                .append(",\n  \"ticks\": ").append(scenario.ticks())
                .append(",\n  \"ticksRun\": ").append(ticksRun)
                .append(",\n  \"complete\": ").append(complete)
                .append(days < 0 ? "" : String.format(java.util.Locale.ROOT, ",\n  \"days\": %.3f", days))
                .append(factions == null ? "" : factions)
                .append(items == null ? "" : items)
                .append(server == null ? "" : server)
                .append(",\n  \"players\": {");
        boolean firstPlayer = true;
        for (var entry : players.entrySet()) {
            var p = entry.getValue();
            out.append(firstPlayer ? "\n" : ",\n").append("    ").append(Json.string(entry.getKey())).append(": {\n      \"stages\": {");
            firstPlayer = false;
            boolean first = true;
            for (String stage : STAGES) {
                Long tick = p.stages.get(stage);
                if (tick == null) continue;
                out.append(first ? "" : ", ").append(Json.string(stage)).append(": ").append(tick);
                first = false;
            }
            out.append("},\n      \"deaths\": [");
            first = true;
            for (var d : p.deaths) {
                out.append(first ? "\n" : ",\n").append("        {\"tick\": ").append(d.tick()).append(", \"cause\": ").append(Json.string(d.cause()))
                        .append(", \"x\": ").append(d.x()).append(", \"y\": ").append(d.y()).append(", \"z\": ").append(d.z())
                        .append(", \"light\": ").append(d.light()).append(", \"timeOfDay\": ").append(d.timeOfDay())
                        .append(", \"dimension\": ").append(Json.string(d.dimension())).append(", \"hunger\": ").append(d.hunger())
                        .append(", \"underSky\": ").append(d.underSky()).append(", \"stage\": ").append(Json.string(d.stage()))
                        .append(", \"stance\": ").append(Json.string(d.stance())).append(", \"habits\": ").append(Json.string(d.habits()))
                        .append(", \"task\": ").append(Json.string(d.task())).append('}');
                first = false;
            }
            out.append(first ? "]" : "\n      ]").append(",\n      \"deathsNotListed\": ").append(p.deathsDropped).append(",\n      \"stuck\": [");
            first = true;
            for (var s : p.stuck) {
                out.append(first ? "" : ", ").append("{\"tick\": ").append(s.tick()).append(", \"x\": ").append(s.x())
                        .append(", \"y\": ").append(s.y()).append(", \"z\": ").append(s.z()).append(", \"attempt\": ").append(s.attempt()).append('}');
                first = false;
            }
            out.append("],\n      \"stuckNotListed\": ").append(p.stuckDropped).append(",\n      \"rescues\": [");
            first = true;
            for (var r : p.rescues) {
                out.append(first ? "" : ", ").append("{\"tick\": ").append(r.tick()).append(", \"line\": ").append(Json.string(r.line())).append('}');
                first = false;
            }
            out.append("],\n      \"rescuesNotListed\": ").append(p.rescuesDropped).append(",\n      \"tokens\": ").append(p.tokens)
                    .append(",\n      \"chunkLoads\": ").append(p.chunkLoads)
                    .append(String.format(java.util.Locale.ROOT, ",\n      \"chunkMinutes\": %.1f", p.chunkTicks / 1200.0))
                    .append(",\n      \"bridgeBlocks\": ").append(p.bridgeBlocks)
                    .append(String.format(java.util.Locale.ROOT, ",\n      \"bridgeSeconds\": %.1f", p.bridgeTicks / 20.0)).append("\n    }");
        }
        out.append(players.isEmpty() ? "}" : "\n  }").append("\n}\n");
        return out.toString();
    }
}
