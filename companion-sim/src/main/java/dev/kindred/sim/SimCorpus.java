package dev.kindred.sim;

import dev.kindred.core.atlas.Priors;
import dev.kindred.sim.SimWorld.Block;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In plain terms: the failure corpus played in the simulator (F-35f). Each scene a player died in on a server, its ground
 * and the player's state then, is staged in the simulator's world and played for as long as the corpus plays it; the
 * simulator's outcome (alive at the end or not) is set beside the game's, and the share where they agree is reported.
 *
 * Technical: {@code corpus <scenes folder> <out folder>}. The scenes are written by {@code tools/sim/corpus_scenes.py}
 * from the corpus on {@code main}, in the simulator's own block and item names; only those whose ground maps to blocks
 * the simulator knows are written. Each is staged in an empty {@link SimWorld}: bedrock at the bottom, the ground at its
 * own heights, its corner at 48, 48 of the square, and nothing else round it, as a GameTest's structure stands alone;
 * the hostile mobs captured with it stand where they stood, with the health they had.
 * The game's replay ({@code ReplayGameTests.play}) passes when the player is alive at the end of the scene's ticks; so
 * does the simulator's. A scene the game passed or failed on some runs and not others ("intermittent") is played but
 * not counted in the agreement.
 */
public final class SimCorpus {
    static final int CORNER = 48;

    /** One scene: its id, the game's outcome, and how the simulator's run went. */
    record Played(String id, String game, boolean simPassed, int deaths, int stuck, String ended) { }

    public static void main(String[] args) throws IOException {
        Path scenes = Path.of(args[0]), out = Path.of(args[1]);
        Files.createDirectories(out);
        var priors = Priors.standard();
        List<Path> files;
        try (var list = Files.list(scenes)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".scene")).sorted().toList();
        }
        var played = files.parallelStream().map(file -> play(file, priors, out)).toList();
        int agree = 0, decided = 0, simPass = 0;
        var lines = new ArrayList<String>();
        var by = new LinkedHashMap<String, int[]>();
        for (Played p : played) {
            if (p == null) continue;
            if (p.simPassed()) simPass++;
            boolean counted = p.game().equals("pass") || p.game().equals("fail");
            boolean same = counted && p.game().equals("pass") == p.simPassed();
            if (counted) {
                decided++;
                if (same) agree++;
            }
            by.computeIfAbsent(p.game() + " / " + (p.simPassed() ? "sim pass" : "sim fail"), k -> new int[1])[0]++;
            lines.add(String.format(Locale.ROOT, "%s\tgame %s\tsim %s\tdeaths %d\tstuck %d\t%s%s", p.id(), p.game(), p.simPassed() ? "pass" : "fail",
                    p.deaths(), p.stuck(), p.ended(), counted ? (same ? "\tagree" : "\tdiffer") : ""));
        }
        lines.add("");
        for (Map.Entry<String, int[]> e : by.entrySet()) lines.add(e.getKey() + ": " + e.getValue()[0]);
        String summary = String.format(Locale.ROOT, "%d scenes played (%d passed in the simulator); %d with a game outcome of pass or fail, "
                + "%d agree: %.1f%%", played.stream().filter(p -> p != null).count(), simPass, decided, agree, decided == 0 ? 0 : 100.0 * agree / decided);
        lines.add(summary);
        Files.write(out.resolve("agreement.txt"), lines, StandardCharsets.UTF_8);
        System.out.println(summary);
    }

    private static Played play(Path file, Priors priors, Path out) {
        try {
            var fields = new LinkedHashMap<String, String>();
            var items = new LinkedHashMap<String, Integer>();
            var mobs = new java.util.ArrayList<String[]>();
            var world = SimWorld.staged(file.getFileName().toString().hashCode());
            for (int x = 0; x < SimWorld.SIZE; x++) for (int z = 0; z < SimWorld.SIZE; z++) world.set(x, SimWorld.MIN_Y, z, Block.BEDROCK);
            int oy = 0;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                int space = line.indexOf(' ');
                String key = line.substring(0, space), value = line.substring(space + 1);
                if (key.equals("origin")) oy = Integer.parseInt(value.split(" ")[1]);
                if (key.equals("item")) {
                    String[] parts = value.split(" ");
                    items.merge(parts[0], Integer.parseInt(parts[1]), Integer::sum);
                } else if (key.equals("mob")) {
                    mobs.add(value.split(" "));
                } else if (key.equals("block")) {
                    String[] parts = value.split(" ");
                    world.set(CORNER + Integer.parseInt(parts[0]), oy + Integer.parseInt(parts[1]), CORNER + Integer.parseInt(parts[2]),
                            Block.valueOf(parts[3]));
                } else {
                    fields.put(key, value);
                }
            }
            String id = fields.get("id");
            String[] at = fields.get("at").split(" ");
            int[] feet = {CORNER + (int) Math.floor(Double.parseDouble(at[0])), oy + (int) Math.floor(Double.parseDouble(at[1])),
                    CORNER + (int) Math.floor(Double.parseDouble(at[2]))};
            world.tick = Long.parseLong(fields.get("time"));
            long ticks = Long.parseLong(fields.get("ticks"));
            var run = new SimRun(new SimRun.Config("corpus", id.hashCode(), ticks, SimRun.Start.FRESH, fields.get("name")), priors, world, feet, items);
            run.body(Float.parseFloat(fields.get("health")), Integer.parseInt(fields.get("food")), Float.parseFloat(fields.get("saturation")));
            if (fields.containsKey("air")) run.air(Integer.parseInt(fields.get("air")));
            for (String[] mob : mobs) {
                run.mob(mob[0], CORNER + Double.parseDouble(mob[1]), oy + Double.parseDouble(mob[2]), CORNER + Double.parseDouble(mob[3]), Float.parseFloat(mob[4]));
            }
            var result = run.run();
            Path folder = out.resolve(id);
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("latest.log"), result.log(), StandardCharsets.UTF_8);
            String log = result.log();
            String last = log.isEmpty() ? "" : log.substring(log.lastIndexOf('\n', log.length() - 2) + 1).trim();
            return new Played(id, fields.get("outcome"), result.deaths() == 0, result.deaths(), result.stuck(), last);
        } catch (IOException | RuntimeException failed) {
            System.err.println(file + ": " + failed);
            return null;
        }
    }
}
