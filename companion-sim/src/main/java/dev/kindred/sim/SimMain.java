package dev.kindred.sim;

import dev.kindred.core.atlas.Priors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.LongStream;

/**
 * In plain terms: runs the simulator's scenario packs, many seeds each, and writes each run as an evaluation run is
 * written, so that {@code tools/eval/score.py} scores them as it scores the server's; then says how long it took, the
 * deaths an hour, and anything odd the planner did.
 *
 * Technical: F-28. {@code <pack|all> <seeds> <out folder>}: seeds 1 to n, run in parallel; each run's
 * {@code eval-result.json} and {@code latest.log} under {@code <out>/<pack>/<seed>/}, and the pack's anomalies (a step
 * the planner asked for twenty times running while the simulator refused it, with nothing new seen) in
 * {@code <out>/<pack>/anomalies.txt}. The packs are data: where a run starts, with what, and for how long. With
 * {@code corpus <scenes> <out>}, the failure corpus's scenes instead ({@link SimCorpus}, F-35f).
 */
public final class SimMain {
    /** A scenario pack: its name, how a run begins and how many game ticks it lasts. */
    public record Pack(String name, SimRun.Start start, long ticks) { }

    public static final List<Pack> PACKS = List.of(
            new Pack("night", SimRun.Start.DUSK, 12_000),
            new Pack("caving", SimRun.Start.CAVE, 24_000),
            new Pack("portal", SimRun.Start.FRESH, 72_000));

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("corpus")) {
            SimCorpus.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        String which = args.length > 0 ? args[0] : "all";
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        Path out = Path.of(args.length > 2 ? args[2] : "run/sim");
        var priors = Priors.standard();
        long all = System.nanoTime();
        for (Pack pack : PACKS) {
            if (!which.equals("all") && !which.equals(pack.name())) continue;
            long started = System.nanoTime();
            var results = LongStream.rangeClosed(1, seeds).parallel()
                    .mapToObj(seed -> new Object[] {seed, new SimRun(new SimRun.Config(pack.name(), seed, pack.ticks(), pack.start(), "Kindred"), priors).run()})
                    .toList();
            double seconds = (System.nanoTime() - started) / 1e9;
            var anomalies = new ArrayList<String>();
            int deaths = 0, stuck = 0;
            for (Object[] each : results) {
                long seed = (Long) each[0];
                var result = (SimRun.Result) each[1];
                Path folder = out.resolve(pack.name()).resolve(String.valueOf(seed));
                Files.createDirectories(folder);
                Files.writeString(folder.resolve("eval-result.json"), result.json(), StandardCharsets.UTF_8);
                Files.writeString(folder.resolve("latest.log"), result.log(), StandardCharsets.UTF_8);
                deaths += result.deaths();
                stuck += result.stuck();
                for (String anomaly : result.anomalies()) anomalies.add("seed " + seed + ": " + anomaly);
            }
            Files.write(out.resolve(pack.name()).resolve("anomalies.txt"), anomalies, StandardCharsets.UTF_8);
            double hours = seeds * pack.ticks() / 72_000.0;
            System.out.printf(Locale.ROOT, "%s: %d seeds in %.1f s (%.2f s a game hour); %d deaths, %.2f a game hour; %d stuck spells; %d anomalies%n",
                    pack.name(), seeds, seconds, seconds / hours * Runtime.getRuntime().availableProcessors(), deaths, deaths / hours, stuck, anomalies.size());
        }
        System.out.printf(Locale.ROOT, "all: %.1f s%n", (System.nanoTime() - all) / 1e9);
    }
}
