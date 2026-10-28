package dev.kindred.testkit;

import dev.kindred.core.tactics.LearnedMelee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Trains the learned melee offline (E-41e): every demonstrations file under a directory (a server's
 * {@code kindred/demonstrations}), read in a fixed order, fitted, and the model written as text with its version and
 * held-out score. Nothing is promoted by training: a server uses a model only once an operator puts it in place and
 * turns it on, and turns it off again to go back to the hand-set melee.
 *
 * <p>Usage: {@code gradlew :companion-testkit:trainMelee --args="<demonstrations directory> <model file>"}.
 */
public final class TrainMelee {
    private TrainMelee() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: TrainMelee <demonstrations directory> <model file>");
            System.exit(2);
        }
        List<Path> files;
        try (var walk = Files.walk(Path.of(args[0]))) {
            files = walk.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
        }
        var samples = new ArrayList<LearnedMelee.Sample>();
        int skipped = 0;
        for (Path file : files) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith(LearnedMelee.SAMPLE_HEADER)) continue;
                try {
                    samples.add(LearnedMelee.sample(line));
                } catch (IllegalArgumentException bad) {
                    skipped++;
                }
            }
        }
        System.out.printf("%d demonstrations from %d files (%d lines skipped)%n", samples.size(), files.size(), skipped);
        var model = LearnedMelee.train(samples, 40, 0.05, 1e-5);
        Files.writeString(Path.of(args[1]), LearnedMelee.write(model), StandardCharsets.UTF_8);
        System.out.printf("Model %s for %s: trained on %d, %.1f%% of the held-out moves made%n", model.version(), model.ruleset(),
                model.trainedOn(), 100 * model.heldOut());
    }
}
