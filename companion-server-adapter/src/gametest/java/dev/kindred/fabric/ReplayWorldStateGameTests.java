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

    /**
     * A dragon scene's replay has the dragon fight on, as its recording server had it, and a portal scene's replay
     * running beside it does not: the fight is each replay runtime's own, read from its own edits.properties, and gone
     * with it. Staged: two replay runtimes made from the two policies, live at once, then stopped.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "replayDragonFight", tickLimit = 400)
    public void aDragonScenesReplayHasTheFightOnAndAPortalScenesBesideItDoesNot(TestContext context) {
        var server = context.getWorld().getServer();
        var dragonScene = new com.google.gson.JsonObject();
        dragonScene.addProperty("goal", "DRAGON");
        var portalScene = new com.google.gson.JsonObject();
        portalScene.addProperty("goal", "NETHER_PORTAL");
        PlayerRuntime dragon = runtime(server, dragonScene), portal = runtime(server, portalScene);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(dragon.isReady() && portal.isReady(), "Both ready"))
                .createAndAdd(() -> {
                    context.assertTrue(dragon.dragonFightAllowed(), "The dragon scene's replay has the fight on");
                    context.assertFalse(portal.dragonFightAllowed(), "The portal scene's replay, live beside it, does not");
                    context.assertFalse(ReplayGameTests.edits(portalScene).contains("dragonFight"), "No fight in a portal scene's policy");
                    dragon.stop();
                    portal.stop();
                })
                .createAndAdd(() -> context.assertTrue(dragon.shutdownComplete() && portal.shutdownComplete(), "Both stopped"))
                .completeIfSuccessful();
    }

    private static PlayerRuntime runtime(net.minecraft.server.MinecraftServer server, com.google.gson.JsonObject scene) {
        var directory = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("kindred-replay-" + java.util.UUID.randomUUID());
        try {
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve("edits.properties"), ReplayGameTests.edits(scene));
        } catch (java.io.IOException error) {
            throw new RuntimeException("Replay edit policy could not be saved", error);
        }
        var runtime = new PlayerRuntime(server, directory, NativePlayerSaves::read, writer -> writer);
        runtime.start();
        return runtime;
    }
}
