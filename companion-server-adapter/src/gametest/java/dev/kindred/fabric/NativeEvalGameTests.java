package dev.kindred.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In plain terms: a GameTest: an evaluation run of two hundred ticks spawns its starter, sets it to the Nether goal,
 * lets the ticks run and writes its result file, which is read back: the scenario as given, earned, the ticks it ran,
 * and a record for the starter. And a starter whose spot is a tree's crown is made on the ground beside it, so that the
 * run has every starter it asked for.
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
                        throw new net.minecraft.test.GameTestException("No readable result at " + out + ": " + unreadable);
                    }
                    context.assertTrue(result.get("scenario").getAsString().equals("starters=Kindred; goal=nether; edits=on; ticks=200; sprint=off; strategist=off;"
                            + " voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on"), "The scenario: " + result.get("scenario"));
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

    /**
     * The spot a starter is spawned at is the top of a tree's trunk in its crown, as at seed 404's spawn in a jungle,
     * where the roster spawn finds no room: the starter is made on the ground beside the tree, and the run says nothing
     * of a starter that never came. Before, the run went on without it (two of three in each ten-seed run of seed 404).
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "evalSpawn", tickLimit = 3000)
    public void aStarterWhoseSpotIsATreesCrownIsMadeOnTheGroundBesideIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        Path out = f.directory.resolve("eval-result.json");
        boolean[] ended = {false};
        EvalBootstrap[] run = new EvalBootstrap[1];
        BlockPos origin = context.getAbsolutePos(new BlockPos(30, 1, 30));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, origin.add(-3, 0, -3), origin.add(3, 9, 3));
                    // A trunk six logs high, its crown of leaves round and over its top.
                    for (int y = 0; y < 6; y++) world.setBlockState(origin.up(y), Blocks.JUNGLE_LOG.getDefaultState());
                    for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 5; y <= 7; y++) {
                        BlockPos leaf = origin.add(x, y, z);
                        if (world.getBlockState(leaf).isAir()) world.setBlockState(leaf, Blocks.JUNGLE_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
                    }
                    context.assertTrue(world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, origin).getY() == origin.getY() + 6, "The spot on the trunk's top");
                    run[0] = EvalBootstrap.forTest("starters=Kindred; goal=none; ticks=40; sprint=off", out, () -> ended[0] = true, origin);
                    context.runAtEveryTick(() -> {
                        if (run[0] != null) run[0].tick(context.getWorld().getServer(), f.runtime);
                    });
                })
                .createAndAdd(() -> context.assertTrue(ended[0], "The run has not ended"))
                .createAndAdd(() -> {
                    JsonObject result;
                    try {
                        result = JsonParser.parseString(Files.readString(out)).getAsJsonObject();
                    } catch (Exception unreadable) {
                        throw new net.minecraft.test.GameTestException("No readable result at " + out + ": " + unreadable);
                    }
                    context.assertTrue(!result.get("build").getAsString().contains("not every starter came"), "Every starter came: " + result.get("build"));
                    // Its own starter, by the fixture's runtime: not by name, which another fixture's player may share in a full run.
                    var kindred = f.runtime.nativeStatuses(dev.kindred.core.Actor.console()).stream()
                            .filter(s -> s.name().equals("Kindred") && s.connected()).findFirst()
                            .map(s -> context.getWorld().getServer().getPlayerManager().getPlayer(s.nativeId())).orElse(null);
                    context.assertTrue(kindred != null && kindred.getBlockY() <= origin.getY() + 1, "Made on the ground beside the tree: "
                            + (kindred == null ? "not in the world" : kindred.getBlockPos().toShortString()));
                    run[0] = null;
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
