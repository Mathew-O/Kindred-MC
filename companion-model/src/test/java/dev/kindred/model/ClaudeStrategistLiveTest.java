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

    @Test
    void repeatedHungerBringsAFoodFocusAndAHabit() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINDRED_LIVE_MODEL_TEST") != null, "live model test not requested");
        var strategist = ClaudeStrategist.fromEnvironment(null);
        Assumptions.assumeTrue(strategist.isPresent(), "no ANTHROPIC_API_KEY");
        try (var s = strategist.get()) {
            String report = """
                    Player Kindred, overworld, day 3, daytime (time 2000).
                    Position 40 64 80, on the surface.
                    Health 9/20, food 3/20, food items carried 0.
                    Stage: Iron. Doing: Iron: Mine iron ore (4/15) - Branch mine for iron.
                    Gear: pickaxe tier 2 (2 carried), sword tier 2, a shield, no armour.
                    Deaths so far: 2. No-progress spells: 1.
                    Stance in force: PRESS_ON, focus NONE.
                    Habits kept in this world: none.
                    Recent events, oldest first:
                    - at 900s: died at 35 22 87: Kindred starved to death while below ground (stage Iron)
                    - at 1800s: climbed out hurt and out of food
                    - at 2100s: died at 38 19 90: Kindred was slain by Zombie (stage Iron), with no food left
                    Your notes from earlier in this world:
                    - Ran out of food deep in the branch mine""";
            var outcome = s.advise(report, List.of(new StrategyAdvice.Place(35, 22, 87), new StrategyAdvice.Place(38, 19, 90)))
                    .get(90, TimeUnit.SECONDS);
            System.out.println("strategist outcome: " + outcome);
            assertTrue(outcome.advice().isPresent(), "advice: " + outcome.failure());
            assertTrue(outcome.advice().get().focus() == dev.kindred.core.survival.PlanPreferences.Focus.FOOD, "hungry with none carried: food first");
        }
    }
}
