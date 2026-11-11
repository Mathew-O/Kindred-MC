package dev.kindred.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * F-38: core reasons by tags, components and attributes, not by vanilla ids. A {@code minecraft:} string literal
 * may stand only in {@link VanillaIds} (and the vanilla needs resource, which is not Java). While the refactor
 * moves package by package, {@code f38-literal-baseline.txt} lists each main source file's remaining count: a file
 * may not gain one, a file not listed may hold none, and a file that lost some must have its line lowered, so the
 * count only falls. At zero the baseline is empty and the rule is the plain one.
 */
class VanillaIdsLiteralTest {

    private static final Path MAIN = Path.of("src/main/java/dev/kindred/core");
    private static final Pattern LITERAL = Pattern.compile("\"#?minecraft:");
    private static final String ALLOWED = "VanillaIds.java";

    @Test
    void noVanillaIdLiteralOutsideVanillaIdsBeyondTheBaseline() throws IOException {
        Map<String, Integer> baseline = baseline();
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (file.getFileName().toString().equals(ALLOWED)) continue;
                int count = count(Files.readString(file, StandardCharsets.UTF_8));
                if (count > 0) found.put(MAIN.relativize(file).toString().replace('\\', '/'), count);
            }
        }
        List<String> problems = new ArrayList<>();
        found.forEach((file, count) -> {
            int allowed = baseline.getOrDefault(file, 0);
            if (count > allowed) {
                problems.add(file + ": " + count + " minecraft: literals, " + allowed
                        + " allowed; name the id in VanillaIds with the tag it would use");
            }
        });
        baseline.forEach((file, allowed) -> {
            int count = found.getOrDefault(file, 0);
            if (count < allowed) {
                problems.add(file + ": " + count + " minecraft: literals left; lower its baseline line from "
                        + allowed);
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static int count(String source) {
        Matcher matcher = LITERAL.matcher(source);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static Map<String, Integer> baseline() throws IOException {
        Map<String, Integer> baseline = new TreeMap<>();
        try (InputStream in = VanillaIdsLiteralTest.class.getResourceAsStream("f38-literal-baseline.txt")) {
            if (in == null) return baseline;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\s+");
                baseline.put(parts[0], Integer.parseInt(parts[1]));
            }
        }
        return baseline;
    }
}
