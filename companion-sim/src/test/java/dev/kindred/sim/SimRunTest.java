package dev.kindred.sim;

import dev.kindred.core.atlas.Priors;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: the simulator plays the same game from the same seed, and writes its log and result as the server
 * writes its own, so that the evaluation's scorer reads them unchanged.
 *
 * Technical: F-28. The line forms are {@code RunMetrics}'s own patterns, copied here (the module depends on
 * {@code companion-core} only); {@code tools/eval/score.py} running {@code RunMetrics} over the packs' logs is the
 * check that they agree.
 */
class SimRunTest {
    private static final Pattern STAMP = Pattern.compile("^\\[(\\d\\d):(\\d\\d):(\\d\\d)\\] \\[[^\\]]*\\](?: \\(Kindred\\)|:) (.*)$");
    private static final Pattern OUTCOME = Pattern.compile("^(\\S+) (native_\\w+) (COMPLETED|BLOCKED|CANCELLED)(?:: (.*?))?(?: \\[(.*)\\])?$");
    private static final Pattern GOAL = Pattern.compile("^(\\S+) goal at -?\\d+ -?\\d+ -?\\d+: ([^:]+)(?::.*)?$");

    private static SimRun.Result play(SimRun.Start start, long seed, long ticks) {
        return new SimRun(new SimRun.Config("test", seed, ticks, start, "Kindred"), Priors.standard()).run();
    }

    @Test
    void theSameSeedPlaysTheSameGame() {
        var first = play(SimRun.Start.FRESH, 7, 24_000);
        var second = play(SimRun.Start.FRESH, 7, 24_000);
        assertEquals(first.log(), second.log());
        assertEquals(first.json(), second.json());
        assertTrue(!play(SimRun.Start.FRESH, 8, 24_000).log().equals(first.log()), "another seed, another game");
    }

    @Test
    void itsLogIsInTheServersForms() {
        for (var start : SimRun.Start.values()) {
            var result = play(start, 3, 12_000);
            int goals = 0, outcomes = 0;
            for (String line : result.log().split("\n")) {
                var stamped = STAMP.matcher(line);
                assertTrue(stamped.matches(), "a server line: " + line);
                String text = stamped.group(4);
                if (text.contains(" goal at ")) {
                    assertTrue(GOAL.matcher(text).matches(), "a goal line: " + text);
                    goals++;
                } else if (text.matches("^\\S+ native_\\w+ .*")) {
                    assertTrue(OUTCOME.matcher(text).matches(), "a task's end: " + text);
                    outcomes++;
                }
            }
            assertTrue(goals > 0 && outcomes > 0, start + ": goals " + goals + ", task ends " + outcomes);
        }
    }

    @Test
    void itsResultIsAnEarnedEvaluationRecord() {
        var result = play(SimRun.Start.FRESH, 5, 24_000);
        String json = result.json();
        assertTrue(json.contains("\"kind\": \"earned\""), json);
        assertTrue(json.contains("\"Kindred\""), json);
        assertTrue(json.contains("\"wooden_tool\""), "a fresh start gets its first pickaxe within a game day: " + json);
        assertTrue(json.contains("\"complete\": true"), json);
    }
}
