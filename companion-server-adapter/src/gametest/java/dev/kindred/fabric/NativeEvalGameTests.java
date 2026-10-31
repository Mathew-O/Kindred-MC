package dev.kindred.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In plain terms: a GameTest: an evaluation run of two hundred ticks spawns its starter, sets it to the Nether goal,
 * lets the ticks run and writes its result file, which is read back: the scenario as given, earned, the ticks it ran,
 * and a record for the starter.
 *
 * Technical: F-26. The run is driven each tick on the fixture's own runtime, round the test's field, with a flag in
 * place of stopping the server and no game rules set (the test server shares one world among its tests); the file is
 * read back with Gson.
 */
public final class NativeEvalGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "evalRun", tickLimit = 3000)
    public void anEvaluationRunOfTwoHundredTicksWritesItsResultAndEnds(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        Path out = f.directory.resolve("eval-result.json");
        boolean[] ended = {false};
        EvalBootstrap[] run = new EvalBootstrap[1];
        BlockPos origin = context.getAbsolutePos(new BlockPos(20, 1, 20));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    run[0] = EvalBootstrap.forTest("starters=Kindred; goal=nether; ticks=200; sprint=off", out, () -> ended[0] = true, origin);
                    context.runAtEveryTick(() -> {
                        if (run[0] != null) run[0].tick(context.getWorld().getServer(), f.runtime);
                    });
                })
                .createAndAdd(() -> context.assertTrue(ended[0], "The run has not ended: " + (run[0] == null ? "" : run[0].recordForTest(context.getWorld().getServer()))))
                .createAndAdd(() -> {
                    JsonObject result;
                    try {
                        result = JsonParser.parseString(Files.readString(out)).getAsJsonObject();
                    } catch (Exception unreadable) {
                        throw new AssertionError("No readable result at " + out + ": " + unreadable);
                    }
                    context.assertTrue(result.get("scenario").getAsString().equals(
                            "starters=Kindred; goal=nether; edits=on; ticks=200; sprint=off; strategist=off; voice=off; keep=none"), "The scenario: " + result.get("scenario"));
                    context.assertTrue(result.get("kind").getAsString().equals("earned"), "Earned: " + result.get("kind"));
                    context.assertTrue(result.get("ticks").getAsLong() == 200 && result.get("ticksRun").getAsLong() >= 200, "Its ticks: " + result);
                    context.assertTrue(result.get("complete").getAsBoolean(), "Complete");
                    context.assertTrue(result.get("seed").getAsLong() == context.getWorld().getSeed(), "Its seed");
                    var kindred = result.getAsJsonObject("players").getAsJsonObject("Kindred");
                    context.assertTrue(kindred != null && kindred.has("stages") && kindred.getAsJsonArray("deaths") != null
                            && kindred.getAsJsonArray("stuck") != null && kindred.has("tokens"), "A record for its starter: " + result);
                    // It came into the world and was set to the goal by the run itself.
                    context.assertTrue(f.runtime.nativeStatuses(dev.kindred.core.Actor.console()).stream().anyMatch(s -> s.name().equals("Kindred") && s.connected()),
                            "Its starter in the world");
                    context.assertTrue(f.runtime.panelBots(dev.kindred.core.Actor.console()).stream().anyMatch(b -> !b.goalProgress().isEmpty()), "Its goal set");
                    run[0] = null;
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
