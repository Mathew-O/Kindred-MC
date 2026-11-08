package dev.kindred.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kindred.core.factions.FactionSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest of the evaluation engine's faction world (M-06i): a short unattended run in which a starter
 * plays a faction world it joined, with nobody at the console, and at its end the whole history read back from the
 * World Record is audited against the locked rules and written into the run's result, with no rule broken.
 *
 * Technical: The scenario {@code mode=factions} through {@link EvalBootstrap#forTest}. The border setting is at its
 * widest (the world's own border), since test positions lie millions of blocks from spawn; the run sets the hub a safe
 * zone at spawn as an operator would.
 */
public final class NativeFactionsEvalGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "59999968"));

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "factionsEval", tickLimit = 4000)
    public void aFactionWorldRunIsAuditedFromTheRecordWithNoRuleBroken(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        Path out = f.directory.resolve("eval-result.json");
        boolean[] ended = {false};
        EvalBootstrap[] run = new EvalBootstrap[1];
        BlockPos origin = context.getAbsolutePos(new BlockPos(20, 1, 20));
        String scenario = "starters=Kindred; goal=none; ticks=1500; sprint=off; mode=factions";
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factions().settings(WIDE);
                    run[0] = EvalBootstrap.forTest(scenario, out, () -> ended[0] = true, origin);
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
                        throw new net.minecraft.test.GameTestException("No readable result at " + out + ": " + unreadable);
                    }
                    context.assertTrue(result.get("scenario").getAsString().endsWith("; mode=factions"), "The scenario: " + result.get("scenario"));
                    context.assertTrue(result.get("kind").getAsString().equals("earned") && result.get("complete").getAsBoolean(), "Earned and complete: " + result);
                    var factions = result.getAsJsonObject("factions");
                    context.assertTrue(factions != null && factions.get("audited").getAsBoolean(), "Audited: " + factions);
                    context.assertTrue(factions.getAsJsonArray("violations").isEmpty(), "No rule broken: " + factions.getAsJsonArray("violations"));
                    var counts = factions.getAsJsonObject("counts");
                    context.assertTrue(counts.get("factions").getAsInt() >= 1 && counts.get("claims").getAsInt() >= 1,
                            "Its starter founded a faction and claimed land on its own: " + counts);
                    run[0] = null;
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
