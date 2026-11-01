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
    }

    private final EvalScenario scenario;
    private final Map<String, Player> players = new LinkedHashMap<>();

    public EvalRecord(EvalScenario scenario) {
        this.scenario = scenario;
        for (String name : scenario.starters()) players.put(name, new Player());
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

    /** The tokens its model players have used so far: the latest total replaces the last. */
    public void tokens(String player, long tokens) {
        var p = players.get(player);
        if (p != null) p.tokens = Math.max(p.tokens, tokens);
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
                .append(",\n  \"kind\": ").append(Json.string(scenario.earned() ? "earned" : "staged"))
                .append(",\n  \"build\": ").append(Json.string(build))
                .append(",\n  \"seed\": ").append(seed)
                .append(",\n  \"ticks\": ").append(scenario.ticks())
                .append(",\n  \"ticksRun\": ").append(ticksRun)
                .append(",\n  \"complete\": ").append(complete)
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
            out.append("],\n      \"rescuesNotListed\": ").append(p.rescuesDropped).append(",\n      \"tokens\": ").append(p.tokens).append("\n    }");
        }
        out.append(players.isEmpty() ? "}" : "\n  }").append("\n}\n");
        return out.toString();
    }
}
