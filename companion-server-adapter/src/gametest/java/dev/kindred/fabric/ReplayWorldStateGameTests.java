package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.world.Difficulty;

/**
 * In plain terms: a replay that plays on easy difficulty, in rain, at night, leaves the server as it found it, so the
 * tests that run after it are not run on a replay's settings.
 *
 * Technical: F-27a, the restore {@link ReplayGameTests} does once the last running replay has ended (CI on #164: the
 * crystal blast fixture reckoned 9.17 and took 5.58, Easy's scaling, after a replay had set Easy). Staged: the server's
 * difficulty, weather and time of day set as a replay sets them, between the record and the restore.
 */
public final class ReplayWorldStateGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "replayWorldState", tickLimit = 100)
    public void aReplaysDifficultyWeatherAndTimeArePutBackOnceItHasEnded(TestContext context) {
        var world = context.getWorld();
        var server = world.getServer();
        var difficulty = server.getSaveProperties().getDifficulty();
        boolean raining = world.isRaining();
        long time = world.getTimeOfDay();
        ReplayGameTests.remember(server, world);
        server.setDifficulty(difficulty == Difficulty.EASY ? Difficulty.HARD : Difficulty.EASY, true);
        world.setWeather(0, 24000, !raining, false);
        world.setTimeOfDay(time + 9000);
        ReplayGameTests.restoreIfNoneLeft(server);
        context.assertTrue(server.getSaveProperties().getDifficulty() == difficulty,
                "The difficulty put back: " + server.getSaveProperties().getDifficulty() + ", was " + difficulty);
        context.assertTrue(world.isRaining() == raining, "The weather put back: raining " + world.isRaining() + ", was " + raining);
        context.assertTrue(world.getTimeOfDay() == time, "The time put back: " + world.getTimeOfDay() + ", was " + time);
        context.complete();
    }
}
