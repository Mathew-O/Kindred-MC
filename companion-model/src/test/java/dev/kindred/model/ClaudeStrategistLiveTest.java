package dev.kindred.model;

import dev.kindred.core.survival.StrategyAdvice;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One real consultation, run only when {@code KINDRED_LIVE_MODEL_TEST} and {@code ANTHROPIC_API_KEY} are set: the
 * ordinary build never calls the network or spends money.
 */
class ClaudeStrategistLiveTest {

    @Test
    void aRealConsultationReturnsValidatedAdvice() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINDRED_LIVE_MODEL_TEST") != null, "live model test not requested");
        var strategist = ClaudeStrategist.fromEnvironment(null);
        Assumptions.assumeTrue(strategist.isPresent(), "no ANTHROPIC_API_KEY");
        try (var s = strategist.get()) {
            String report = """
                    Player Kindred, overworld, night, below ground at 22 40 82. Health 5/20, food 6/20, no food carried.
                    Stage: Iron (2/18 iron). Gear: stone pickaxe, stone sword, no shield, no armour.
                    Recent events, oldest first:
                    - died at 35 32 87: shot by Skeleton (stage Iron)
                    - died at -5 65 97: blown up by Creeper (stage Wood)
                    - outmatched by a skeleton at 23 39 81
                    Danger zones: 35 32 87, -5 65 97""";
            var outcome = s.advise(report, List.of(new StrategyAdvice.Place(35, 32, 87), new StrategyAdvice.Place(-5, 65, 97)))
                    .get(90, TimeUnit.SECONDS);
            System.out.println("strategist outcome: " + outcome);
            assertTrue(outcome.advice().isPresent(), "advice: " + outcome.failure());
            assertTrue(outcome.advice().get().stance() != StrategyAdvice.Stance.PRESS_ON, "a hurt, hungry player that keeps dying should not press on");
        }
    }
}
