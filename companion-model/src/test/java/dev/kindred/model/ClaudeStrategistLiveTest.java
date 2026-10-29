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

    @Test
    void deathsToSkeletonsBeforeTheNetherQueueAFollowUpTheQueueLacks() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINDRED_LIVE_MODEL_TEST") != null, "live model test not requested");
        var strategist = ClaudeStrategist.fromEnvironment(null);
        Assumptions.assumeTrue(strategist.isPresent(), "no ANTHROPIC_API_KEY");
        try (var s = strategist.get()) {
            String report = """
                    Player Kindred, overworld, day 9, daytime (time 3000).
                    Position 412 70 -980, on the surface.
                    Health 14/20, food 17/20, food items carried 9.
                    Stage: Prepare (End). Doing: End: Prepare: Stock food for the Nether - Approach cow.
                    Gear: pickaxe tier 3 (2 carried), sword tier 3, no shield, no armour.
                    Materials: 20 planks' worth of wood, 2 sticks, 30 cobblestone, 4 coal, 0 raw iron, 3 iron ingots, 1 buckets, 0 diamonds, 0 obsidian.
                    Deaths so far: 2. No-progress spells: 0.
                    Stance in force: PRESS_ON, focus NONE.
                    Habits kept in this world: none.
                    Queue: Wanted for what is ahead: bow 1 of 1 (the End fight: to shoot the End crystals off their pillars; they heal the dragon); \
                    arrow 12 of 32 (the End fight: a few for each of the ten crystals, and for the dragon in flight); block 30 of 64 (the End fight: \
                    to pillar up to the two caged crystals and wall off endermen); water bucket 0 of 1 (the End fight: to break a fall from a pillar; \
                    endermen keep out of water); food 9 of 16 (the End fight: there is nothing to eat in the End). Still to do: gather water bucket 1, \
                    gather feathers 5, gather flint 5, gather blocks 34, gather food 7, craft sticks 4, craft arrows 20.
                    Recent events, oldest first:
                    - at 600s: died at 400 60 -990: Kindred was shot by Skeleton (stage Prepare)
                    - at 1500s: died at 420 58 -1002: Kindred was shot by Skeleton (stage Prepare)
                    - at 2000s: outmatched by a skeleton at 415 62 -995""";
            var outcome = s.advise(report, List.of(new StrategyAdvice.Place(400, 60, -990), new StrategyAdvice.Place(420, 58, -1002)))
                    .get(90, TimeUnit.SECONDS);
            System.out.println("strategist outcome: " + outcome);
            assertTrue(outcome.advice().isPresent(), "advice: " + outcome.failure());
            var queue = outcome.advice().get().queue();
            assertTrue(!queue.isEmpty(), "two deaths to skeletons and no shield before the Nether: something to queue");
            assertTrue(queue.stream().noneMatch(w -> w.need() == dev.kindred.core.guide.Need.BOW), "never what it already holds: " + queue);
        }
    }

    @Test
    void aRealReplyToWhatAreYouDoingNamesTheLogsItIsChopping() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINDRED_LIVE_MODEL_TEST") != null, "live model test not requested");
        var strategist = ClaudeStrategist.fromEnvironment(null);
        Assumptions.assumeTrue(strategist.isPresent(), "no ANTHROPIC_API_KEY");
        try (var s = strategist.get()) {
            var situation = new dev.kindred.core.ConversationAudience.Situation("Echo", "Steve", dev.kindred.core.ConversationAudience.Relation.PLAYER,
                    false, 20, 18, "", "the Overworld", "day", "clear", false, true, "chopping logs for a crafting table", "a Nether portal",
                    "Wood: Gather logs for a crafting table", "120, 64, -35", "", List.of("Steve"), List.of("a stone axe"), 3, List.of(),
                    List.of("Cut 3 oak logs at 118, 66, -33"), List.of(), "", List.of());
            var conversation = ConversationPrompt.conversation("Echo", dev.kindred.core.Personality.defaults(), situation, "Echo, what are you doing?");
            var line = s.converse(conversation).get(30, TimeUnit.SECONDS);
            System.out.println("voice line: " + line);
            assertTrue(line.say().isPresent(), "a line: " + line.failure());
            String said = line.say().get().toLowerCase(java.util.Locale.ROOT);
            assertTrue(said.contains("log") || said.contains("wood") || said.contains("tree") || said.contains("chop"), "grounded in what it is doing: " + said);
            assertTrue(line.millis() < Strategist.CHAT_DEADLINE.toMillis(), "within the chat lane's deadline");
        }
    }

    @Test
    void aRealAskFromTheOwnerToComeIsCome() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINDRED_LIVE_MODEL_TEST") != null, "live model test not requested");
        var strategist = ClaudeStrategist.fromEnvironment(null);
        Assumptions.assumeTrue(strategist.isPresent(), "no ANTHROPIC_API_KEY");
        try (var s = strategist.get()) {
            var situation = new dev.kindred.core.ConversationAudience.Situation("Echo", "Steve", dev.kindred.core.ConversationAudience.Relation.OWNER,
                    true, 20, 18, "", "the Overworld", "day", "clear", false, false, "", "", "", "120, 64, -35", "", List.of("Steve"),
                    List.of("a stone axe"), 3, List.of("4 oak logs"), List.of(), List.of(), "", List.of());
            var come = s.converse(ConversationPrompt.conversation("Echo", dev.kindred.core.Personality.defaults(), situation, "come over here please"))
                    .get(30, TimeUnit.SECONDS);
            System.out.println("voice ask: " + come);
            assertTrue(come.say().isPresent() && come.ask() == Strategist.Ask.COME, "an owner's plain ask to come: " + come);
            var chat = s.converse(ConversationPrompt.conversation("Echo", dev.kindred.core.Personality.defaults(), situation, "nice weather today"))
                    .get(30, TimeUnit.SECONDS);
            System.out.println("voice small talk: " + chat);
            assertTrue(chat.say().isPresent() && chat.ask() == Strategist.Ask.NONE, "small talk asks nothing: " + chat);
        }
    }

    /** Latency of each pace on the same report, run only with {@code KINDRED_LIVE_MODEL_BENCH}: three calls each. */
    @Test
    void paceBenchmark() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINDRED_LIVE_MODEL_BENCH") != null, "pace benchmark not requested");
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
        String[][] paces = {{"", "on"}, {"low", "on"}, {"low", "off"}};
        for (String[] pace : paces) {
            var strategist = ClaudeStrategist.fromEnvironment(null, pace[0], pace[1]);
            Assumptions.assumeTrue(strategist.isPresent(), "no ANTHROPIC_API_KEY");
            try (var s = strategist.get()) {
                for (int i = 0; i < 4; i++) {
                    var outcome = s.advise(report, List.of(new StrategyAdvice.Place(35, 22, 87), new StrategyAdvice.Place(38, 19, 90))).get(90, TimeUnit.SECONDS);
                    System.out.println("pace " + s.pace() + ": " + outcome.millis() + " ms, " + outcome.inputTokens() + " in (" + outcome.cachedTokens() + " cached), " + outcome.outputTokens() + " out, "
                            + outcome.advice().map(a -> a.stance() + " " + a.focus() + " " + a.habit()).orElse("no advice: " + outcome.failure()));
                }
            }
        }
    }
}
